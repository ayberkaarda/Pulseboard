package io.pulseboard.core.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.TestcontainersConfiguration;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.IntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Constraints, indexes and grants of {@code V8__alert_rules.sql}. */
class AlertSchemaIntegrationTest extends IntegrationTest {

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

    private UUID insertRule(Seed seed, String name, String condition, String operator, Double threshold,
            String minSeverity, int cooldown, String channel, String target) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO alert_rule (id, tenant_id, metric_id, name, condition, operator, threshold, min_severity,
                                        cooldown_seconds, channel, target)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, seed.tenantId(), seed.metricId(), name, condition, operator, threshold, minSeverity,
                cooldown, channel, target);
        return id;
    }

    private UUID thresholdRule(Seed seed) {
        return insertRule(seed, "rule-" + UUID.randomUUID(), "THRESHOLD", "GT", 100.0, null, 300, "EMAIL_LOG",
                "ops@example.com");
    }

    private void insertEvent(Seed seed, UUID ruleId, String sourceRef) {
        jdbc.update("""
                INSERT INTO alert_event (id, tenant_id, rule_id, triggered_at, payload, next_attempt_at, source_ref)
                VALUES (?, ?, ?, now(), '{}'::jsonb, now(), ?)
                """, UUID.randomUUID(), seed.tenantId(), ruleId, sourceRef);
    }

    @Test
    void migrationEightIsApplied() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).contains("8");
    }

    @Test
    void bothConditionsAreAccepted() {
        Seed seed = seed();
        insertRule(seed, "threshold", "THRESHOLD", "LTE", -5.5, null, 60, "WEBHOOK", "https://hooks.example.com/x");
        insertRule(seed, "severity", "ANOMALY_SEVERITY", null, null, "MEDIUM", 86400, "EMAIL_LOG", "a@b.c");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_rule WHERE tenant_id = ? AND version = 0 "
                + "AND enabled AND created_at IS NOT NULL", Integer.class, seed.tenantId())).isEqualTo(2);
    }

    @Test
    void checkConstraintsRejectInconsistentRules() {
        Seed seed = seed();
        record Bad(String why, String condition, String operator, Double threshold, String severity, int cooldown,
                String channel, String target) {
        }

        List<Bad> bad = List.of(
                new Bad("NaN threshold", "THRESHOLD", "GT", Double.NaN, null, 300, "EMAIL_LOG", "x"),
                new Bad("+Infinity", "THRESHOLD", "GT", Double.POSITIVE_INFINITY, null, 300, "EMAIL_LOG", "x"),
                new Bad("-Infinity", "THRESHOLD", "GT", Double.NEGATIVE_INFINITY, null, 300, "EMAIL_LOG", "x"),
                new Bad("threshold without operator", "THRESHOLD", null, 1.0, null, 300, "EMAIL_LOG", "x"),
                new Bad("threshold without value", "THRESHOLD", "GT", null, null, 300, "EMAIL_LOG", "x"),
                new Bad("threshold with severity", "THRESHOLD", "GT", 1.0, "LOW", 300, "EMAIL_LOG", "x"),
                new Bad("severity without severity", "ANOMALY_SEVERITY", null, null, null, 300, "EMAIL_LOG", "x"),
                new Bad("severity with operator", "ANOMALY_SEVERITY", "GT", null, "LOW", 300, "EMAIL_LOG", "x"),
                new Bad("severity with threshold", "ANOMALY_SEVERITY", null, 1.0, "LOW", 300, "EMAIL_LOG", "x"),
                new Bad("unknown operator", "THRESHOLD", "EQ", 1.0, null, 300, "EMAIL_LOG", "x"),
                new Bad("unknown severity", "ANOMALY_SEVERITY", null, null, "CRITICAL", 300, "EMAIL_LOG", "x"),
                new Bad("unknown condition", "MAGIC", "GT", 1.0, null, 300, "EMAIL_LOG", "x"),
                new Bad("cooldown below 60", "THRESHOLD", "GT", 1.0, null, 59, "EMAIL_LOG", "x"),
                new Bad("cooldown above a day", "THRESHOLD", "GT", 1.0, null, 86401, "EMAIL_LOG", "x"),
                new Bad("unknown channel", "THRESHOLD", "GT", 1.0, null, 300, "SMS", "x"),
                new Bad("blank target", "THRESHOLD", "GT", 1.0, null, 300, "EMAIL_LOG", "  "));
        for (Bad row : bad) {
            assertThatThrownBy(() -> insertRule(seed, "n-" + UUID.randomUUID(), row.condition(), row.operator(),
                    row.threshold(), row.severity(), row.cooldown(), row.channel(), row.target()))
                    .as(row.why())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insertRule(seed, "  ", "THRESHOLD", "GT", 1.0, null, 300, "EMAIL_LOG", "x"))
                .as("blank name").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_rule WHERE tenant_id = ?", Integer.class,
                seed.tenantId())).isZero();
    }

    @Test
    void aRuleNameIsUniquePerTenant() {
        Seed first = seed();
        Seed second = seed();
        insertRule(first, "latency", "THRESHOLD", "GT", 1.0, null, 300, "EMAIL_LOG", "x");

        assertThatThrownBy(() -> insertRule(first, "latency", "THRESHOLD", "GT", 2.0, null, 300, "EMAIL_LOG", "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertRule(second, "latency", "THRESHOLD", "GT", 1.0, null, 300, "EMAIL_LOG", "x");
    }

    @Test
    void aRuleCannotWatchAMetricOfAnotherTenant() {
        Seed first = seed();
        Seed second = seed();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO alert_rule (id, tenant_id, metric_id, name, condition, operator, threshold,
                                        cooldown_seconds, channel, target)
                VALUES (?, ?, ?, 'x', 'THRESHOLD', 'GT', 1, 300, 'EMAIL_LOG', 'x')
                """, UUID.randomUUID(), second.tenantId(), first.metricId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheCreatorKeepsTheRule() {
        Seed seed = seed();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO alert_rule (id, tenant_id, metric_id, name, condition, operator, threshold,
                                        cooldown_seconds, channel, target, created_by)
                VALUES (?, ?, ?, 'x', 'THRESHOLD', 'GT', 1, 300, 'EMAIL_LOG', 'x', ?)
                """, id, seed.tenantId(), seed.metricId(), seed.userId());

        jdbc.update("DELETE FROM app_user WHERE id = ?", seed.userId());

        assertThat(jdbc.queryForObject("SELECT created_by IS NULL FROM alert_rule WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    void anEventIsUniquePerRuleAndSourceAndConflictsAreIgnorable() {
        Seed seed = seed();
        UUID rule = thresholdRule(seed);
        UUID otherRule = thresholdRule(seed);
        insertEvent(seed, rule, "2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> insertEvent(seed, rule, "2026-01-01T00:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertEvent(seed, rule, "2026-01-01T00:01:00Z");
        insertEvent(seed, otherRule, "2026-01-01T00:00:00Z");
        int inserted = jdbc.update("""
                INSERT INTO alert_event (id, tenant_id, rule_id, triggered_at, payload, next_attempt_at, source_ref)
                VALUES (?, ?, ?, now(), '{}'::jsonb, now(), '2026-01-01T00:00:00Z')
                ON CONFLICT (rule_id, source_ref) DO NOTHING
                """, UUID.randomUUID(), seed.tenantId(), rule);
        assertThat(inserted).isZero();
    }

    @Test
    void eventConstraintsRejectBadRows() {
        Seed seed = seed();
        UUID rule = thresholdRule(seed);
        record Bad(String why, String status, boolean delivered, int attempts, String payload) {
        }

        List<Bad> bad = List.of(
                new Bad("unknown status", "DONE", false, 0, "{}"),
                new Bad("SENT without delivery time", "SENT", false, 0, "{}"),
                new Bad("PENDING with delivery time", "PENDING", true, 0, "{}"),
                new Bad("negative attempts", "PENDING", false, -1, "{}"),
                new Bad("payload not an object", "PENDING", false, 0, "[]"));
        for (Bad row : bad) {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO alert_event (id, tenant_id, rule_id, triggered_at, payload, next_attempt_at,
                                             source_ref, delivery_status, delivered_at, attempts)
                    VALUES (?, ?, ?, now(), ?::jsonb, now(), ?, ?, CASE WHEN ? THEN now() END, ?)
                    """, UUID.randomUUID(), seed.tenantId(), rule, row.payload(), "src-" + UUID.randomUUID(),
                    row.status(), row.delivered(), row.attempts()))
                    .as(row.why())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_event WHERE rule_id = ?", Integer.class, rule))
                .isZero();
    }

    @Test
    void anEventCannotReferenceARuleOfAnotherTenant() {
        Seed first = seed();
        Seed second = seed();
        UUID rule = thresholdRule(first);

        assertThatThrownBy(() -> insertEvent(second, rule, "x")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingARuleOrItsMetricRemovesItsEvents() {
        Seed seed = seed();
        UUID rule = thresholdRule(seed);
        insertEvent(seed, rule, "a");
        jdbc.update("DELETE FROM alert_rule WHERE id = ?", rule);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_event WHERE rule_id = ?", Integer.class, rule))
                .isZero();

        UUID second = thresholdRule(seed);
        insertEvent(seed, second, "b");
        jdbc.update("DELETE FROM metric_definition WHERE id = ?", seed.metricId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_rule WHERE id = ?", Integer.class, second))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_event WHERE rule_id = ?", Integer.class, second))
                .isZero();
    }

    @Test
    void theExpectedIndexesExist() {
        List<String> definitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename IN ('alert_rule', 'alert_event')", String.class);

        assertThat(definitions)
                .anyMatch(def -> def.contains("alert_event_tenant_time_idx") && def.contains("triggered_at DESC"))
                .anyMatch(def -> def.contains("alert_event_pending_due_idx")
                        && def.contains("WHERE ((delivery_status)::text = 'PENDING'::text)"))
                .anyMatch(def -> def.contains("alert_rule_metric_enabled_idx") && def.contains("WHERE enabled"));
    }

    @Test
    void theAnalyticsRolesHaveNoAccess() throws SQLException {
        for (String[] role : new String[][] {
            {TestcontainersConfiguration.ANALYTICS_READER_USER, TestcontainersConfiguration.ANALYTICS_READER_PASSWORD},
            {"analytics_writer", TestcontainersConfiguration.ANALYTICS_WRITER_PASSWORD}}) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, role[0], role[1])) {
                // The reader role defaults to read-only transactions; lift that so the privileges are tested.
                try (PreparedStatement statement = connection.prepareStatement(
                        "SET default_transaction_read_only = off")) {
                    statement.execute();
                }
                for (String sql : List.of("SELECT count(*) FROM alert_rule", "SELECT count(*) FROM alert_event",
                        "INSERT INTO alert_rule (id) VALUES (gen_random_uuid())",
                        "DELETE FROM alert_event")) {
                    assertThatThrownBy(() -> {
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.execute();
                        }
                    }).as(role[0] + ": " + sql).isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                }
            }
        }
    }
}
