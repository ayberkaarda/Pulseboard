package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.domain.alert.AlertChannel;
import io.pulseboard.core.domain.alert.AlertOutbox;
import io.pulseboard.core.domain.alert.DueAlert;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDBC implementation of {@link AlertOutbox}. Claims use {@code FOR UPDATE SKIP LOCKED} inside one statement, so
 * dispatchers on several instances take disjoint rows without waiting for each other.
 */
@Repository
public class JdbcAlertOutbox implements AlertOutbox {

    private static final String ABANDONED = "delivery did not complete";
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public JdbcAlertOutbox(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional
    public List<DueAlert> claimDue(Instant now, Instant leaseUntil, int limit, int maxAttempts) {
        OffsetDateTime current = utc(now);
        // A dispatcher that died during the last allowed attempt leaves a PENDING row with an expired lease.
        jdbcTemplate.update("""
                UPDATE alert_event
                   SET delivery_status = 'FAILED', claimed_until = NULL, last_error = ?
                 WHERE delivery_status = 'PENDING' AND attempts >= ? AND claimed_until < ?
                """, ABANDONED, maxAttempts, current);
        return jdbcTemplate.query("""
                WITH due AS (
                    SELECT id FROM alert_event
                     WHERE delivery_status = 'PENDING' AND next_attempt_at <= ?
                       AND (claimed_until IS NULL OR claimed_until < ?)
                     ORDER BY next_attempt_at, id
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED)
                UPDATE alert_event e
                   SET claimed_until = ?, attempts = e.attempts + 1
                  FROM due, alert_rule r
                 WHERE e.id = due.id AND r.id = e.rule_id AND r.tenant_id = e.tenant_id
                RETURNING e.id, e.tenant_id, e.rule_id, r.name AS rule_name, r.enabled, r.channel, r.target,
                          e.payload::text AS payload, e.attempts
                """, this::mapRow, current, current, limit, utc(leaseUntil));
    }

    @Override
    @Transactional
    public void markSent(UUID eventId, TenantId tenantId, Instant at) {
        jdbcTemplate.update("""
                UPDATE alert_event
                   SET delivery_status = 'SENT', delivered_at = ?, claimed_until = NULL, last_error = NULL
                 WHERE id = ? AND tenant_id = ? AND delivery_status = 'PENDING'
                """, utc(at), eventId, tenantId.value());
    }

    @Override
    @Transactional
    public void markFailed(UUID eventId, TenantId tenantId, String error) {
        jdbcTemplate.update("""
                UPDATE alert_event
                   SET delivery_status = 'FAILED', claimed_until = NULL, last_error = ?
                 WHERE id = ? AND tenant_id = ? AND delivery_status = 'PENDING'
                """, error, eventId, tenantId.value());
    }

    @Override
    @Transactional
    public void reschedule(UUID eventId, TenantId tenantId, Instant next, String error) {
        jdbcTemplate.update("""
                UPDATE alert_event
                   SET next_attempt_at = ?, claimed_until = NULL, last_error = ?
                 WHERE id = ? AND tenant_id = ? AND delivery_status = 'PENDING'
                """, utc(next), error, eventId, tenantId.value());
    }

    @Override
    @Transactional
    public void defer(UUID eventId, TenantId tenantId, Instant next) {
        jdbcTemplate.update("""
                UPDATE alert_event
                   SET next_attempt_at = ?, claimed_until = NULL, attempts = GREATEST(attempts - 1, 0)
                 WHERE id = ? AND tenant_id = ? AND delivery_status = 'PENDING'
                """, utc(next), eventId, tenantId.value());
    }

    private DueAlert mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DueAlert(
                rs.getObject("id", UUID.class),
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("rule_id", UUID.class),
                rs.getString("rule_name"),
                rs.getBoolean("enabled"),
                AlertChannel.valueOf(rs.getString("channel")),
                rs.getString("target"),
                parsePayload(rs.getString("payload")),
                rs.getInt("attempts"));
    }

    private Map<String, Object> parsePayload(String json) {
        try {
            return jsonMapper.readValue(json, OBJECT);
        } catch (JacksonException ex) {
            throw new IllegalStateException("alert_event.payload is not a JSON object", ex);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
