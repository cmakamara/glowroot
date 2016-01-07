/*
 * Copyright 2014-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.storage.simplerepo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLongArray;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.common.util.Clock;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.helper.Gauges;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.JdbcQuery;
import org.glowroot.storage.simplerepo.util.DataSource.JdbcRowQuery;
import org.glowroot.storage.simplerepo.util.DataSource.JdbcUpdate;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.Schemas.Column;
import org.glowroot.storage.simplerepo.util.Schemas.ColumnType;
import org.glowroot.storage.simplerepo.util.Schemas.Index;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static org.glowroot.storage.simplerepo.util.Checkers.castUntainted;

public class GaugeValueDao implements GaugeValueRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_id", ColumnType.BIGINT),
            ImmutableColumn.of("capture_time", ColumnType.BIGINT),
            ImmutableColumn.of("value", ColumnType.DOUBLE),
            // weight is needed for rollups
            // for non-counters, it is the number of recording that the (averaged) value represents
            // for counters, it is the interval of time that the (averaged) value represents
            ImmutableColumn.of("weight", ColumnType.BIGINT));

    private final GaugeDao gaugeMetaDao;
    private final DataSource dataSource;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final ImmutableList<RollupConfig> rollupConfigs;

    // AtomicLongArray used for visibility
    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    GaugeValueDao(DataSource dataSource, GaugeDao gaugeMetaDao,
            ConfigRepository configRepository, Clock clock) throws Exception {
        this.dataSource = dataSource;
        this.gaugeMetaDao = gaugeMetaDao;
        this.configRepository = configRepository;
        this.clock = clock;
        this.rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());

        for (int i = 0; i <= rollupConfigs.size(); i++) {
            dataSource.syncTable("gauge_value_rollup_" + castUntainted(i), columns);
            dataSource.syncIndexes("gauge_value_rollup_" + castUntainted(i),
                    ImmutableList.<Index>of(ImmutableIndex.of(
                            "gauge_value_rollup_" + castUntainted(i) + "_idx",
                            ImmutableList.of("gauge_id", "capture_time", "value", "weight"))));
        }
        List<Column> columns = Lists.newArrayList();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            columns.add(ImmutableColumn.of("last_rollup_" + i + "_time", ColumnType.BIGINT));
        }
        dataSource.syncTable("gauge_value_last_rollup_times", columns);

        List<String> columnNames = Lists.newArrayList();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            columnNames.add("last_rollup_" + i + "_time");
        }
        Joiner joiner = Joiner.on(", ");
        String selectClause = castUntainted(joiner.join(columnNames));
        long[] lastRollupTimes = dataSource.query(new LastRollupTimesQuery(selectClause));
        if (lastRollupTimes == null) {
            long[] values = new long[rollupConfigs.size()];
            String valueClause = castUntainted(joiner.join(Longs.asList(values)));
            dataSource.update("insert into gauge_value_last_rollup_times (" + selectClause
                    + ") values (" + valueClause + ")");
            this.lastRollupTimes = new AtomicLongArray(values);
        } else {
            this.lastRollupTimes = new AtomicLongArray(lastRollupTimes);
        }

        // TODO initial rollup in case store is not called in a reasonable time
    }

    @Override
    public List<Gauge> getGauges(String serverRollup) throws Exception {
        List<String> allGaugeNames = gaugeMetaDao.readAllGaugeNames();
        List<Gauge> gauges = Lists.newArrayList();
        for (String gaugeName : allGaugeNames) {
            gauges.add(Gauges.getGauge(gaugeName));
        }
        return gauges;
    }

    @Override
    public void store(String serverId, List<GaugeValue> gaugeValues) throws Exception {
        if (gaugeValues.isEmpty()) {
            return;
        }
        dataSource.batchUpdate(new GaugeValuesBinder(gaugeValues));
        synchronized (rollupLock) {
            // clock can never go backwards and future gauge captures will wait until this method
            // completes since ScheduledExecutorService.scheduleAtFixedRate() guarantees that future
            // invocations of GaugeCollector will wait until prior invocations complete
            //
            // TODO this clock logic will fail if remote collectors are introduced
            long safeCurrentTime = clock.currentTimeMillis() - 1;
            for (int i = 0; i < rollupConfigs.size(); i++) {
                long intervalMillis = rollupConfigs.get(i).intervalMillis();
                long safeRollupTime =
                        RollupLevelService.getSafeRollupTime(safeCurrentTime, intervalMillis);
                long lastRollupTime = lastRollupTimes.get(i);
                if (safeRollupTime > lastRollupTime) {
                    rollup(lastRollupTime, safeRollupTime, intervalMillis, i + 1, i);
                    // JVM termination here will cause last_rollup_*_time to be out of sync, which
                    // will cause a re-rollup of this time after the next startup, but this possible
                    // duplicate is filtered out by the distinct clause in readGaugeValues()
                    dataSource.update("update gauge_value_last_rollup_times set last_rollup_"
                            + castUntainted(i + 1) + "_time = ?", safeRollupTime);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    @Override
    public List<GaugeValue> readGaugeValues(String serverRollup, String gaugeName,
            long from, long to, int rollupLevel) throws Exception {
        Long gaugeId = gaugeMetaDao.getGaugeId(gaugeName);
        if (gaugeId == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        // the distinct clause is needed for the rollup tables in order to handle corner case where
        // JVM termination occurs in between rollup and updating gauge_value_last_rollup_times
        // in which case a duplicate entry will occur after the next startup
        return dataSource.query(new GaugeValueQuery(gaugeId, from, to, rollupLevel));
    }

    @Override
    public void deleteAll(String serverRollup) throws Exception {
        dataSource.execute("truncate table gauge_value_rollup_0");
        for (int i = 1; i <= configRepository.getRollupConfigs().size(); i++) {
            dataSource.execute("truncate table gauge_value_rollup_" + castUntainted(i));
        }
        gaugeMetaDao.deleteAll();
    }

    void deleteBefore(long captureTime, int rollupLevel) throws Exception {
        dataSource.deleteBefore("gauge_value_rollup_" + castUntainted(rollupLevel), captureTime);
    }

    private void rollup(long lastRollupTime, long safeRollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        // TODO handle when offset is different for lastRollupTime and safeRollupTime?
        int offsetMillis = TimeZone.getDefault().getOffset(safeRollupTime);
        // need ".0" to force double result
        String captureTimeSql = castUntainted("ceil((capture_time + " + offsetMillis + ") / "
                + fixedIntervalMillis + ".0) * " + fixedIntervalMillis + " - " + offsetMillis);
        rollup(lastRollupTime, safeRollupTime, captureTimeSql, toRollupLevel, fromRollupLevel);
    }

    private void rollup(long lastRollupTime, long safeRollupTime, @Untainted String captureTimeSql,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        dataSource.update("insert into gauge_value_rollup_" + castUntainted(toRollupLevel)
                + " (gauge_id, capture_time, value, weight) select gauge_id, " + captureTimeSql
                + " ceil_capture_time, sum(value * weight) / sum(weight), sum(weight)"
                + " from gauge_value_rollup_" + castUntainted(fromRollupLevel)
                + " gp where gp.capture_time > ? and gp.capture_time <= ?"
                + " group by gp.gauge_id, ceil_capture_time", lastRollupTime, safeRollupTime);
    }

    private class GaugeValuesBinder implements JdbcUpdate {

        private final List<GaugeValue> gaugeValues;

        private GaugeValuesBinder(List<GaugeValue> gaugeValues) {
            this.gaugeValues = gaugeValues;
        }

        @Override
        public @Untainted String getSql() {
            return "insert into gauge_value_rollup_0 (gauge_id, capture_time, value, weight)"
                    + " values (?, ?, ?, ?)";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            for (GaugeValue gaugeValue : gaugeValues) {
                long gaugeId = gaugeMetaDao.updateLastCaptureTime(gaugeValue.getGaugeName(),
                        gaugeValue.getCaptureTime());
                if (gaugeId == -1) {
                    // data source is closing and a new gauge id was needed, but could not
                    // insert it, but this bind is already inside of the data source lock so any
                    // inserts here will succeed, thus the return
                    //
                    // --or-- race condition with GaugeMetaDao.deleteAll() in which case return
                    // is the best option also
                    return;
                }
                preparedStatement.setLong(1, gaugeId);
                preparedStatement.setLong(2, gaugeValue.getCaptureTime());
                preparedStatement.setDouble(3, gaugeValue.getValue());
                preparedStatement.setLong(4, gaugeValue.getWeight());
                preparedStatement.addBatch();
            }
        }
    }

    private static class LastRollupTimesQuery implements JdbcQuery<long/*@Nullable*/[]> {

        private final @Untainted String selectClause;

        public LastRollupTimesQuery(@Untainted String selectClause) {
            this.selectClause = selectClause;
        }

        @Override
        public @Untainted String getSql() {
            return "select " + selectClause + " from gauge_value_last_rollup_times";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {}

        @Override
        public long/*@Nullable*/[] processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                return null;
            }
            int columns = resultSet.getMetaData().getColumnCount();
            long[] values = new long[columns];
            for (int i = 0; i < columns; i++) {
                values[i] = resultSet.getLong(i + 1);
            }
            return values;
        }

        @Override
        public long/*@Nullable*/[] valueIfDataSourceClosing() {
            return null;
        }
    }

    private class GaugeValueQuery implements JdbcRowQuery<GaugeValue> {

        private final long gaugeId;
        private final long from;
        private final long to;
        private final int rollupLevel;

        private GaugeValueQuery(long gaugeId, long from, long to, int rollupLevel) {
            this.gaugeId = gaugeId;
            this.from = from;
            this.to = to;
            this.rollupLevel = rollupLevel;
        }

        @Override
        public @Untainted String getSql() {
            return "select distinct capture_time, value, weight from gauge_value_rollup_"
                    + castUntainted(rollupLevel) + " where gauge_id = ? and capture_time >= ?"
                    + " and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setLong(1, gaugeId);
            preparedStatement.setLong(2, from);
            preparedStatement.setLong(3, to);
        }

        @Override
        public GaugeValue mapRow(ResultSet resultSet) throws SQLException {
            return GaugeValue.newBuilder()
                    .setCaptureTime(resultSet.getLong(1))
                    .setValue(resultSet.getDouble(2))
                    .setWeight(resultSet.getLong(3))
                    .build();
        }
    }
}
