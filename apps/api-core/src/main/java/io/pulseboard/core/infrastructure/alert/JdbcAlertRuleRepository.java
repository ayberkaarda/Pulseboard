package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.domain.alert.AlertChannel;
import io.pulseboard.core.domain.alert.AlertCondition;
import io.pulseboard.core.domain.alert.AlertRule;
import io.pulseboard.core.domain.alert.AlertRuleDraft;
import io.pulseboard.core.domain.alert.AlertRuleRepository;
import io.pulseboard.core.domain.alert.ComparisonOperator;
import io.pulseboard.core.domain.anomaly.Severity;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of {@link AlertRuleRepository}. Every statement filters on {@code tenant_id}, and the metric
 * join repeats the tenant. User supplied values are always bound parameters.
 */
@Repository
public class JdbcAlertRuleRepository implements AlertRuleRepository {

    private static final String SELECT = """
            SELECT r.id, r.tenant_id, r.metric_id, m.name AS metric_name, m.unit AS metric_unit,
                   s.name AS service_name, r.name, r.condition, r.operator, r.threshold, r.min_severity,
                   r.cooldown_seconds, r.channel, r.target, r.enabled, r.created_by, r.created_at, r.updated_at,
                   r.version
              FROM alert_rule r
              JOIN metric_definition m ON m.id = r.metric_id AND m.tenant_id = r.tenant_id
              JOIN service s ON s.id = m.service_id AND s.tenant_id = m.tenant_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AlertRule> rowMapper = this::mapRow;

    public JdbcAlertRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public Optional<AlertRule> create(TenantId tenantId, UUID id, UUID createdBy, AlertRuleDraft draft, Instant now,
            int maxRules) {
        // Serialises concurrent creations of one tenant, so the count below cannot be outrun.
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "alert_rule:" + tenantId);
        Long existing = jdbcTemplate.queryForObject("SELECT count(*) FROM alert_rule WHERE tenant_id = ?",
                Long.class, tenantId.value());
        if (existing != null && existing >= maxRules) {
            return Optional.empty();
        }
        OffsetDateTime at = utc(now);
        jdbcTemplate.update("""
                INSERT INTO alert_rule (id, tenant_id, metric_id, name, condition, operator, threshold,
                                        min_severity, cooldown_seconds, channel, target, enabled, created_by,
                                        created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, id, tenantId.value(), draft.metricId(), draft.name(), draft.condition().name(),
                name(draft.operator()), draft.threshold(), name(draft.minSeverity()), draft.cooldownSeconds(),
                draft.channel().name(), draft.target(), draft.enabled(), createdBy, at, at);
        return findByIdAndTenantId(id, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AlertRule> findByIdAndTenantId(UUID id, TenantId tenantId) {
        return jdbcTemplate.query(SELECT + " WHERE r.tenant_id = ? AND r.id = ?", rowMapper, tenantId.value(), id)
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertRule> findAllByTenantId(TenantId tenantId) {
        return jdbcTemplate.query(SELECT + " WHERE r.tenant_id = ? ORDER BY r.created_at, r.id", rowMapper,
                tenantId.value());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertRule> findEnabledByTenantIdAndMetricIdIn(TenantId tenantId, Collection<UUID> metricIds,
            AlertCondition condition) {
        if (metricIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(SELECT + """
                 WHERE r.tenant_id = ? AND r.enabled AND r.condition = ? AND r.metric_id = ANY (?)
                 ORDER BY r.created_at, r.id
                """, rowMapper, tenantId.value(), condition.name(), metricIds.toArray(UUID[]::new));
    }

    @Override
    @Transactional
    public Optional<AlertRule> update(TenantId tenantId, UUID id, long expectedVersion, AlertRuleDraft draft,
            Instant now) {
        int changed = jdbcTemplate.update("""
                UPDATE alert_rule
                   SET metric_id = ?, name = ?, condition = ?, operator = ?, threshold = ?, min_severity = ?,
                       cooldown_seconds = ?, channel = ?, target = ?, enabled = ?, updated_at = ?,
                       version = version + 1
                 WHERE id = ? AND tenant_id = ? AND version = ?
                """, draft.metricId(), draft.name(), draft.condition().name(), name(draft.operator()),
                draft.threshold(), name(draft.minSeverity()), draft.cooldownSeconds(), draft.channel().name(),
                draft.target(), draft.enabled(), utc(now), id, tenantId.value(), expectedVersion);
        return changed == 0 ? Optional.empty() : findByIdAndTenantId(id, tenantId);
    }

    @Override
    @Transactional
    public Optional<AlertRule> setEnabled(TenantId tenantId, UUID id, boolean enabled, Long expectedVersion,
            Instant now) {
        int changed;
        if (expectedVersion == null) {
            changed = jdbcTemplate.update("""
                    UPDATE alert_rule SET enabled = ?, updated_at = ?, version = version + 1
                     WHERE id = ? AND tenant_id = ?
                    """, enabled, utc(now), id, tenantId.value());
        } else {
            changed = jdbcTemplate.update("""
                    UPDATE alert_rule SET enabled = ?, updated_at = ?, version = version + 1
                     WHERE id = ? AND tenant_id = ? AND version = ?
                    """, enabled, utc(now), id, tenantId.value(), expectedVersion);
        }
        return changed == 0 ? Optional.empty() : findByIdAndTenantId(id, tenantId);
    }

    @Override
    @Transactional
    public int deleteByIdAndTenantId(UUID id, TenantId tenantId) {
        return jdbcTemplate.update("DELETE FROM alert_rule WHERE id = ? AND tenant_id = ?", id, tenantId.value());
    }

    private AlertRule mapRow(ResultSet rs, int rowNum) throws SQLException {
        double threshold = rs.getDouble("threshold");
        Double thresholdValue = rs.wasNull() ? null : threshold;
        String operator = rs.getString("operator");
        String minSeverity = rs.getString("min_severity");
        return new AlertRule(
                rs.getObject("id", UUID.class),
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("metric_id", UUID.class),
                rs.getString("metric_name"),
                rs.getString("metric_unit"),
                rs.getString("service_name"),
                rs.getString("name"),
                AlertCondition.valueOf(rs.getString("condition")),
                operator == null ? null : ComparisonOperator.valueOf(operator),
                thresholdValue,
                minSeverity == null ? null : Severity.valueOf(minSeverity),
                rs.getInt("cooldown_seconds"),
                AlertChannel.valueOf(rs.getString("channel")),
                rs.getString("target"),
                rs.getBoolean("enabled"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getLong("version"));
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
