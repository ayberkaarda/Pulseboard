package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.catalog.Aggregation;
import io.pulseboard.core.domain.metrics.AggregateOverflowException;
import io.pulseboard.core.domain.metrics.MetricSample;
import io.pulseboard.core.domain.metrics.MetricSampleRepository;
import io.pulseboard.core.domain.metrics.SampleBucket;
import io.pulseboard.core.domain.metrics.SampleWriteOutcome;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of {@link MetricSampleRepository} for the hot ingestion path (no ORM).
 *
 * <p>Writes are one JDBC batch of {@code INSERT ... ON CONFLICT (metric_id, ts) DO UPDATE} statements. Each
 * statement inserts only when the metric belongs to the writing tenant ({@code SELECT ... FROM metric_definition
 * WHERE id = ? AND tenant_id = ?}), so a foreign or unknown metric affects zero rows and is reported as
 * {@link SampleWriteOutcome#UNKNOWN_METRIC} without a separate lookup and without a foreign-key error.
 *
 * <p>{@code metric_point} has no default partition, so a row outside every partition would abort the whole
 * batch. The partition bounds are therefore read from the catalog in the same transaction, and samples outside
 * them are reported as {@link SampleWriteOutcome#OUTSIDE_STORAGE_RANGE} and not sent to the database.
 */
@Repository
public class JdbcMetricSampleRepository implements MetricSampleRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO metric_point (metric_id, ts, value)
            SELECT d.id, ?, ?
              FROM metric_definition d
             WHERE d.id = ?
               AND d.tenant_id = ?
            ON CONFLICT (metric_id, ts) DO UPDATE SET value = EXCLUDED.value
            """;

    /**
     * Bounds of every {@code metric_point} partition. The bound literals are printed and cast back in the same
     * session time zone, so the round trip is exact whatever that zone is.
     */
    private static final String PARTITION_BOUNDS_SQL = """
            SELECT bounds[1]::timestamptz AS range_start, bounds[2]::timestamptz AS range_end
              FROM (SELECT regexp_match(pg_catalog.pg_get_expr(c.relpartbound, c.oid),
                                        'FROM \\(''([^'']+)''\\) TO \\(''([^'']+)''\\)') AS bounds
                      FROM pg_catalog.pg_inherits i
                      JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid
                     WHERE i.inhparent = 'public.metric_point'::regclass) partitions
             WHERE bounds IS NOT NULL
            """;

    private static final String DOWNSAMPLE_SQL = """
            SELECT date_bin(make_interval(secs => ?::double precision), p.ts,
                            TIMESTAMPTZ '1970-01-01 00:00:00+00') AS bucket_start,
                   %s AS bucket_value,
                   count(*) AS sample_count
              FROM metric_point p
              JOIN metric_definition d ON d.id = p.metric_id
             WHERE d.tenant_id = ?
               AND p.metric_id = ?
               AND p.ts >= ?
               AND p.ts < ?
             GROUP BY bucket_start
             ORDER BY bucket_start
            """;

    /** PostgreSQL SQLSTATE of a numeric overflow ({@code value out of range: overflow}). */
    private static final String NUMERIC_OVERFLOW = "22003";
    /** PostgreSQL SQLSTATE of a statement cancelled by {@code statement_timeout}. */
    private static final String QUERY_CANCELED = "57014";

    /** Write order: by metric, then timestamp. Every writer locks shared rows in this order, so none deadlock. */
    private static final Comparator<MetricSample> LOCK_ORDER = Comparator
            .comparing(MetricSample::metricId)
            .thenComparing(MetricSample::timestamp);

    private final JdbcTemplate jdbcTemplate;
    private final Duration queryStatementTimeout;

    public JdbcMetricSampleRepository(JdbcTemplate jdbcTemplate,
            @Value("${pulseboard.query.statement-timeout}") Duration queryStatementTimeout) {
        if (queryStatementTimeout.toMillis() < 1) {
            // PostgreSQL reads 0 as "no timeout", so a sub-millisecond value must not be rounded down to it.
            throw new IllegalArgumentException("pulseboard.query.statement-timeout must be at least 1ms");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.queryStatementTimeout = queryStatementTimeout;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Samples with the same metric and timestamp are collapsed first (the last one wins, the earlier ones are
     * {@link SampleWriteOutcome#SUPERSEDED}), and the remaining statements are sent ordered by
     * {@code (metric_id, ts)}. Two batches touching the same rows therefore take their row locks in the same order
     * and cannot deadlock, whatever order the senders used.
     */
    @Override
    @Transactional
    public List<SampleWriteOutcome> upsert(TenantId tenantId, List<MetricSample> samples) {
        List<TimeRange> partitions = jdbcTemplate.query(PARTITION_BOUNDS_SQL, (rs, rowNum) -> new TimeRange(
                rs.getObject("range_start", OffsetDateTime.class).toInstant(),
                rs.getObject("range_end", OffsetDateTime.class).toInstant()));

        List<SampleWriteOutcome> outcomes = new ArrayList<>(samples.size());
        Map<SampleKey, Integer> lastIndexByKey = new HashMap<>();
        for (int i = 0; i < samples.size(); i++) {
            MetricSample sample = samples.get(i);
            boolean covered = partitions.stream().anyMatch(range -> range.contains(sample.timestamp()));
            outcomes.add(covered ? null : SampleWriteOutcome.OUTSIDE_STORAGE_RANGE);
            if (covered) {
                Integer earlier = lastIndexByKey.put(new SampleKey(sample.metricId(), sample.timestamp()), i);
                if (earlier != null) {
                    outcomes.set(earlier, SampleWriteOutcome.SUPERSEDED);
                }
            }
        }
        List<Integer> writable = new ArrayList<>(lastIndexByKey.values());
        if (writable.isEmpty()) {
            return outcomes;
        }
        writable.sort(Comparator.comparing(samples::get, LOCK_ORDER));

        int[] counts = jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int i) throws SQLException {
                MetricSample sample = samples.get(writable.get(i));
                statement.setObject(1, OffsetDateTime.ofInstant(sample.timestamp(), ZoneOffset.UTC));
                statement.setDouble(2, sample.value());
                statement.setObject(3, sample.metricId());
                statement.setObject(4, tenantId.value());
            }

            @Override
            public int getBatchSize() {
                return writable.size();
            }
        });
        for (int i = 0; i < counts.length; i++) {
            outcomes.set(writable.get(i),
                    counts[i] > 0 ? SampleWriteOutcome.STORED : SampleWriteOutcome.UNKNOWN_METRIC);
        }
        return outcomes;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The statement runs under a transaction-local {@code statement_timeout}
     * ({@code pulseboard.query.statement-timeout}), so one expensive range cannot hold a pooled connection
     * indefinitely; a cancelled statement surfaces as {@link QueryTimeoutException}. A numeric overflow of the
     * aggregate surfaces as {@link AggregateOverflowException}, never as a constraint conflict.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SampleBucket> downsample(TenantId tenantId, UUID metricId, Aggregation aggregation, Instant from,
            Instant to, Duration step) {
        jdbcTemplate.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class,
                queryStatementTimeout.toMillis() + "ms");
        String sql = DOWNSAMPLE_SQL.formatted(aggregateExpression(aggregation));
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                double value = rs.getDouble("bucket_value");
                return new SampleBucket(rs.getObject("bucket_start", OffsetDateTime.class).toInstant(),
                        rs.wasNull() ? null : value, rs.getLong("sample_count"));
            }, (double) step.toSeconds(), tenantId.value(), metricId,
                    OffsetDateTime.ofInstant(from, ZoneOffset.UTC), OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        } catch (DataAccessException ex) {
            String sqlState = ex.getMostSpecificCause() instanceof SQLException sqlException
                    ? sqlException.getSQLState() : null;
            if (QUERY_CANCELED.equals(sqlState)) {
                throw new QueryTimeoutException("The metric query exceeded the statement timeout", ex);
            }
            if (NUMERIC_OVERFLOW.equals(sqlState)) {
                throw new AggregateOverflowException("The aggregate exceeds the double range", ex);
            }
            throw ex;
        }
    }

    /** Fixed SQL per aggregation; nothing user-supplied is ever concatenated into the statement. */
    private static String aggregateExpression(Aggregation aggregation) {
        return switch (aggregation) {
            case AVG -> "avg(p.value)";
            case SUM -> "sum(p.value)";
            case MAX -> "max(p.value)";
            case P95 -> "percentile_cont(0.95) WITHIN GROUP (ORDER BY p.value)";
        };
    }

    /** Identity of a row in {@code metric_point}. */
    private record SampleKey(UUID metricId, Instant timestamp) {
    }

    /** Half-open range {@code [start, end)}. */
    private record TimeRange(Instant start, Instant end) {

        boolean contains(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }
    }
}
