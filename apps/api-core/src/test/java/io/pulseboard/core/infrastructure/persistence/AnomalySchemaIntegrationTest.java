package io.pulseboard.core.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.TestcontainersConfiguration;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.IntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Constraints and least-privilege grants of {@code V5__anomalies.sql}. The grant tests connect with the real
 * {@code analytics_reader} and {@code analytics_writer} roles created by the production init script.
 */
class AnomalySchemaIntegrationTest extends IntegrationTest {

    private static final String WRITER = "analytics_writer";
    private static final String INSERT_ANOMALY = """
            INSERT INTO anomaly (id, tenant_id, metric_id, window_start, window_end, score, detector, severity)
            VALUES (?, ?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:05:00Z', 0.8, 'ZSCORE', 'HIGH')
            ON CONFLICT (metric_id, detector, window_start, window_end) DO NOTHING
            RETURNING id
            """;
    private static final String OK_WINDOW = "'2026-01-01T00:00:00Z', '2026-01-01T00:05:00Z', ";

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    private record Seed(UUID tenantId, UUID userId, UUID metricId) {
    }

    private Seed seed() {
        TokenResponse owner = registerTenant();
        UUID serviceId = createService(owner.accessToken(), "checkout").id();
        UUID metricId = createMetric(owner.accessToken(), serviceId, "latency_p95_ms").id();
        return new Seed(owner.tenantId(), owner.userId(), metricId);
    }

    /** Inserts a row for the seed's tenant and metric; {@code values} starts after (id, tenant_id, metric_id). */
    private void insertAnomaly(Seed seed, String values) {
        jdbc.update("INSERT INTO anomaly (id, tenant_id, metric_id, window_start, window_end, score, detector, "
                + "severity, details, acknowledged_by, acknowledged_at) VALUES (?, ?, ?, " + values,
                UUID.randomUUID(), seed.tenantId(), seed.metricId());
    }

    @Test
    void migrationFiveIsApplied() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).contains("5");
    }

    @Test
    void aValidRowIsAcceptedAndDefaultsApply() {
        Seed seed = seed();
        insertAnomaly(seed, OK_WINDOW + "0.5, 'ISOLATION_FOREST', 'LOW', '{}', NULL, NULL)");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE metric_id = ? AND created_at IS NOT NULL "
                + "AND details = '{}'::jsonb", Integer.class, seed.metricId())).isEqualTo(1);
    }

    @Test
    void checkConstraintsRejectBadRows() {
        Seed seed = seed();
        String[] badRows = {
            OK_WINDOW + "1.01, 'ZSCORE', 'LOW', '{}', NULL, NULL)",
            OK_WINDOW + "-0.01, 'ZSCORE', 'LOW', '{}', NULL, NULL)",
            OK_WINDOW + "'NaN'::float8, 'ZSCORE', 'LOW', '{}', NULL, NULL)",
            "'2026-01-01T00:05:00Z', '2026-01-01T00:00:00Z', 0.5, 'ZSCORE', 'LOW', '{}', NULL, NULL)",
            OK_WINDOW + "0.5, 'MAGIC', 'LOW', '{}', NULL, NULL)",
            OK_WINDOW + "0.5, 'ZSCORE', 'CRITICAL', '{}', NULL, NULL)",
            OK_WINDOW + "0.5, 'ZSCORE', 'low', '{}', NULL, NULL)",
            OK_WINDOW + "0.5, 'ZSCORE', 'LOW', '[]', NULL, NULL)",
            OK_WINDOW + "0.5, 'ZSCORE', 'LOW', '{}', '" + seed.userId() + "', NULL)",
        };
        for (String row : badRows) {
            assertThatThrownBy(() -> insertAnomaly(seed, row))
                    .as(row)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE metric_id = ?", Integer.class,
                seed.metricId())).isZero();
    }

    @Test
    void aWindowIsUniquePerMetricAndDetector() {
        Seed seed = seed();
        String row = OK_WINDOW + "0.5, 'ZSCORE', 'LOW', '{}', NULL, NULL)";
        insertAnomaly(seed, row);

        assertThatThrownBy(() -> insertAnomaly(seed, row)).isInstanceOf(DataIntegrityViolationException.class);
        // The same window found by the other detector is a different finding.
        insertAnomaly(seed, OK_WINDOW + "0.5, 'ISOLATION_FOREST', 'LOW', '{}', NULL, NULL)");
    }

    @Test
    void anAnomalyCannotPointAtAMetricOfAnotherTenant() {
        Seed first = seed();
        Seed second = seed();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO anomaly (id, tenant_id, metric_id, window_start, "
                + "window_end, score, detector, severity) VALUES (?, ?, ?, now(), now(), 0.5, 'ZSCORE', 'LOW')",
                UUID.randomUUID(), second.tenantId(), first.metricId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAcknowledgingUserMustBelongToTheSameTenant() {
        Seed first = seed();
        Seed second = seed();

        assertThatThrownBy(() -> insertAnomaly(first, OK_WINDOW + "0.5, 'ZSCORE', 'LOW', '{}', '"
                + second.userId() + "', now())")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheAcknowledgingUserKeepsTheAcknowledgementTime() {
        Seed seed = seed();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO anomaly (id, tenant_id, metric_id, window_start, window_end, score, detector, "
                + "severity, acknowledged_by, acknowledged_at) VALUES (?, ?, ?, now(), now(), 0.5, 'ZSCORE', "
                + "'LOW', ?, now())", id, seed.tenantId(), seed.metricId(), seed.userId());

        jdbc.update("DELETE FROM app_user WHERE id = ?", seed.userId());

        assertThat(jdbc.queryForObject("SELECT acknowledged_by IS NULL AND acknowledged_at IS NOT NULL "
                + "AND tenant_id = ? FROM anomaly WHERE id = ?", Boolean.class, seed.tenantId(), id)).isTrue();
    }

    @Test
    void deletingAMetricRemovesItsAnomaliesAndDetectorState() {
        Seed seed = seed();
        insertAnomaly(seed, OK_WINDOW + "0.5, 'ZSCORE', 'LOW', '{}', NULL, NULL)");
        jdbc.update("INSERT INTO detector_state (metric_id, detector, params, trained_at, version) "
                + "VALUES (?, 'ZSCORE', '{}', now(), 1)", seed.metricId());

        jdbc.update("DELETE FROM metric_definition WHERE id = ?", seed.metricId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE metric_id = ?", Integer.class,
                seed.metricId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM detector_state WHERE metric_id = ?", Integer.class,
                seed.metricId())).isZero();
    }

    @Test
    void detectorStateConstraintsRejectBadRows() {
        Seed seed = seed();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO detector_state VALUES (?, 'MAGIC', '{}', now(), 1)",
                seed.metricId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO detector_state VALUES (?, 'ZSCORE', '{}', now(), 0)",
                seed.metricId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO detector_state VALUES (?, 'ZSCORE', NULL, now(), 1)",
                seed.metricId())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO detector_state VALUES (?, 'ZSCORE', '{}', now(), 1)", seed.metricId());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO detector_state VALUES (?, 'ZSCORE', '{}', now(), 2)",
                seed.metricId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void analyticsWriterHasExactlyTheGrantedPrivileges() throws SQLException {
        Seed seed = seed();
        UUID anomalyId = UUID.randomUUID();
        try (Connection writer = connect(WRITER, TestcontainersConfiguration.ANALYTICS_WRITER_PASSWORD)) {
            // INSERT ... ON CONFLICT DO NOTHING RETURNING: the row comes back once, a replay returns nothing.
            assertThat(insertReturning(writer, anomalyId, seed)).isTrue();
            assertThat(insertReturning(writer, UUID.randomUUID(), seed)).isFalse();
            assertThat(scalar(writer, "SELECT count(*) FROM anomaly WHERE metric_id = '" + seed.metricId() + "'"))
                    .isEqualTo(1);

            execute(writer, "INSERT INTO detector_state VALUES ('" + seed.metricId()
                    + "', 'ZSCORE', '{\"k\":1}', now(), 1)");
            execute(writer, "UPDATE detector_state SET version = 2 WHERE metric_id = '" + seed.metricId() + "'");

            assertDenied(writer, "UPDATE anomaly SET severity = 'LOW' WHERE id = '" + anomalyId + "'");
            assertDenied(writer, "UPDATE anomaly SET acknowledged_at = now()");
            assertDenied(writer, "DELETE FROM anomaly WHERE id = '" + anomalyId + "'");
            assertDenied(writer, "TRUNCATE anomaly");
            assertDenied(writer, "DELETE FROM detector_state");
            assertDenied(writer, "INSERT INTO metric_point (metric_id, ts, value) VALUES ('" + seed.metricId()
                    + "', now(), 1)");
            assertDenied(writer, "SELECT count(*) FROM metric_point");
            assertDenied(writer, "SELECT count(*) FROM metric_definition");
            assertDenied(writer, "UPDATE metric_definition SET unit = 'x'");
            assertDenied(writer, "DELETE FROM metric_definition");
            assertDenied(writer, "INSERT INTO metric_definition (id, tenant_id, service_id, name, unit, aggregation) "
                    + "VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'x', 'ms', 'AVG')");
            assertDenied(writer, "SELECT count(*) FROM app_user");
            assertDenied(writer, "SELECT count(*) FROM tenant");
            assertDenied(writer, "SELECT ensure_metric_partitions(1)");
        }
        assertThat(jdbc.queryForObject("SELECT severity FROM anomaly WHERE id = ?", String.class, anomalyId))
                .isEqualTo("HIGH");
    }

    @Test
    void analyticsReaderCanOnlyReadMetricPointsAndDefinitions() throws SQLException {
        Seed seed = seed();
        insertAnomaly(seed, OK_WINDOW + "0.5, 'ZSCORE', 'LOW', '{}', NULL, NULL)");
        jdbc.update("INSERT INTO metric_point (metric_id, ts, value) VALUES (?, now(), 1.5)", seed.metricId());

        try (Connection reader = connect(TestcontainersConfiguration.ANALYTICS_READER_USER,
                TestcontainersConfiguration.ANALYTICS_READER_PASSWORD)) {
            // The role defaults to read-only transactions; lift that so the privileges themselves are tested.
            execute(reader, "SET default_transaction_read_only = off");
            assertThat(scalar(reader, "SELECT count(*) FROM metric_point WHERE metric_id = '" + seed.metricId()
                    + "'")).isEqualTo(1);
            assertThat(scalar(reader, "SELECT count(*) FROM metric_definition d JOIN metric_point p "
                    + "ON p.metric_id = d.id WHERE d.tenant_id = '" + seed.tenantId() + "'")).isEqualTo(1);

            assertDenied(reader, "SELECT count(*) FROM anomaly");
            assertDenied(reader, "SELECT count(*) FROM detector_state");
            assertDenied(reader, "SELECT count(*) FROM app_user");
            assertDenied(reader, "SELECT count(*) FROM service");
            assertDenied(reader, "INSERT INTO anomaly (id, tenant_id, metric_id, window_start, window_end, score, "
                    + "detector, severity) VALUES (gen_random_uuid(), '" + seed.tenantId() + "', '"
                    + seed.metricId() + "', now(), now(), 0.5, 'ZSCORE', 'LOW')");
            assertDenied(reader, "INSERT INTO detector_state VALUES ('" + seed.metricId()
                    + "', 'ZSCORE', '{}', now(), 1)");
            assertDenied(reader, "UPDATE metric_definition SET unit = 'x'");
            assertDenied(reader, "DELETE FROM metric_definition");
            assertDenied(reader, "INSERT INTO metric_point (metric_id, ts, value) VALUES ('" + seed.metricId()
                    + "', now(), 2)");
        }
    }

    private Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private boolean insertReturning(Connection connection, UUID id, Seed seed) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ANOMALY)) {
            statement.setObject(1, id);
            statement.setObject(2, seed.tenantId());
            statement.setObject(3, seed.metricId());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static void assertDenied(Connection connection, String sql) {
        assertThatThrownBy(() -> execute(connection, sql))
                .as(sql)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }
}
