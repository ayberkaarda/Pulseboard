package io.pulseboard.core.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.TestcontainersConfiguration;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.MetricDefinitionResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.support.IntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the Flyway schema: migration history, metric_point partitioning and the analytics role grants. */
class SchemaAndPartitionIntegrationTest extends IntegrationTest {

    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MetricPartitionMaintenance partitionMaintenance;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${pulseboard.partitions.months-ahead}")
    private int monthsAhead;

    @Test
    void allMigrationsAreApplied() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void partitionsExistFromCurrentMonthUpToMonthsAhead() {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        List<String> expected = IntStream.rangeClosed(0, monthsAhead)
                .mapToObj(offset -> "metric_point_" + current.plusMonths(offset).format(PARTITION_SUFFIX))
                .toList();

        List<String> partitions = jdbcTemplate.queryForList("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'metric_point'::regclass
                """, String.class);

        assertThat(partitions).containsAll(expected);
    }

    @Test
    void ensurePartitionsIsIdempotent() {
        partitionMaintenance.ensurePartitions();

        assertThat(partitionMaintenance.ensurePartitions()).isZero();
    }

    @Test
    void ensurePartitionsRejectsNegativeHorizon() {
        assertThatThrownBy(() -> jdbcTemplate.queryForObject("SELECT ensure_metric_partitions(-1)", Integer.class))
                .hasMessageContaining("months_ahead must be between 0 and 120");
    }

    @Test
    void pointsAreRoutedToTheMonthlyPartition() {
        MetricDefinitionResponse metric = newMetric();
        Instant now = Instant.now();

        jdbcTemplate.update("INSERT INTO metric_point (metric_id, ts, value) VALUES (?, ?, ?)", metric.id(),
                Timestamp.from(now), 42.0);

        String partition = jdbcTemplate.queryForObject(
                "SELECT tableoid::regclass::text FROM metric_point WHERE metric_id = ?", String.class, metric.id());
        assertThat(partition).isEqualTo("metric_point_" + YearMonth.now(ZoneOffset.UTC).format(PARTITION_SUFFIX));
    }

    @Test
    void analyticsReaderCanOnlyReadMetricPoints() throws SQLException {
        MetricDefinitionResponse metric = newMetric();
        jdbcTemplate.update("INSERT INTO metric_point (metric_id, ts, value) VALUES (?, now(), 1.5)", metric.id());

        try (Connection reader = DriverManager.getConnection(jdbcUrl,
                TestcontainersConfiguration.ANALYTICS_READER_USER,
                TestcontainersConfiguration.ANALYTICS_READER_PASSWORD);
                Statement statement = reader.createStatement()) {
            try (var rs = statement.executeQuery(
                    "SELECT count(*) FROM metric_point WHERE metric_id = '" + metric.id() + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM app_user"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO metric_point (metric_id, ts, value) VALUES ('" + metric.id() + "', now(), 2)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private MetricDefinitionResponse newMetric() {
        TokenResponse owner = registerTenant();
        ServiceResponse service = createService(owner.accessToken(), "checkout");
        return createMetric(owner.accessToken(), service.id(), "latency_p95_ms");
    }
}
