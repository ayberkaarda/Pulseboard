package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.domain.alert.AlertEvent;
import io.pulseboard.core.domain.alert.AlertEventPage;
import io.pulseboard.core.domain.alert.AlertEventQuery;
import io.pulseboard.core.domain.alert.AlertEventRepository;
import io.pulseboard.core.domain.alert.DeliveryStatus;
import io.pulseboard.core.domain.alert.NewAlertEvent;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDBC implementation of {@link AlertEventRepository}. Every statement filters on {@code tenant_id}; the rule join
 * repeats it.
 */
@Repository
public class JdbcAlertEventRepository implements AlertEventRepository {

    private static final String SELECT = """
            SELECT e.id, e.tenant_id, e.rule_id, r.name AS rule_name, e.triggered_at, e.payload::text AS payload,
                   e.delivery_status, e.attempts, e.next_attempt_at, e.last_error, e.delivered_at, e.source_ref
              FROM alert_event e
              JOIN alert_rule r ON r.id = e.rule_id AND r.tenant_id = e.tenant_id
            """;
    private static final String COUNT = "SELECT count(*) FROM alert_event e";
    private static final String ORDER = " ORDER BY e.triggered_at DESC, e.id DESC";
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final RowMapper<AlertEvent> rowMapper = this::mapRow;

    public JdbcAlertEventRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional
    public boolean insertIfAbsent(TenantId tenantId, NewAlertEvent event) {
        String payload;
        try {
            payload = jsonMapper.writeValueAsString(event.payload());
        } catch (JacksonException ex) {
            throw new IllegalStateException("The alert payload cannot be serialised", ex);
        }
        // The rule is selected with the tenant, so an event can only be created for a rule of that tenant.
        int inserted = jdbcTemplate.update("""
                INSERT INTO alert_event (id, tenant_id, rule_id, triggered_at, payload, next_attempt_at, source_ref)
                SELECT ?, r.tenant_id, r.id, ?, ?::jsonb, ?, ?
                  FROM alert_rule r
                 WHERE r.id = ? AND r.tenant_id = ?
                ON CONFLICT (rule_id, source_ref) DO NOTHING
                """, event.id(), utc(event.triggeredAt()), payload, utc(event.triggeredAt()), event.sourceRef(),
                event.ruleId(), tenantId.value());
        return inserted == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AlertEvent> findByIdAndTenantId(UUID id, TenantId tenantId) {
        return jdbcTemplate.query(SELECT + " WHERE e.tenant_id = ? AND e.id = ?", rowMapper, tenantId.value(), id)
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public AlertEventPage search(TenantId tenantId, AlertEventQuery query, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE e.tenant_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId.value());
        if (query.ruleId() != null) {
            where.append(" AND e.rule_id = ?");
            arguments.add(query.ruleId());
        }
        if (query.status() != null) {
            where.append(" AND e.delivery_status = ?");
            arguments.add(query.status().name());
        }
        if (query.from() != null) {
            where.append(" AND e.triggered_at >= ?");
            arguments.add(utc(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND e.triggered_at < ?");
            arguments.add(utc(query.to()));
        }
        Long total = jdbcTemplate.queryForObject(COUNT + where, Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        List<AlertEvent> items = jdbcTemplate.query(SELECT + where + ORDER + " LIMIT ? OFFSET ?", rowMapper,
                pageArguments.toArray());
        return new AlertEventPage(items, total == null ? 0 : total);
    }

    private AlertEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime deliveredAt = rs.getObject("delivered_at", OffsetDateTime.class);
        return new AlertEvent(
                rs.getObject("id", UUID.class),
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("rule_id", UUID.class),
                rs.getString("rule_name"),
                rs.getObject("triggered_at", OffsetDateTime.class).toInstant(),
                parsePayload(rs.getString("payload")),
                DeliveryStatus.valueOf(rs.getString("delivery_status")),
                rs.getInt("attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                rs.getString("last_error"),
                deliveredAt == null ? null : deliveredAt.toInstant(),
                rs.getString("source_ref"));
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
