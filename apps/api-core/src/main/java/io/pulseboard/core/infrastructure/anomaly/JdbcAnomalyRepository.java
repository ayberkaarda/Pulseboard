package io.pulseboard.core.infrastructure.anomaly;

import io.pulseboard.core.domain.anomaly.Anomaly;
import io.pulseboard.core.domain.anomaly.AnomalyPage;
import io.pulseboard.core.domain.anomaly.AnomalyQuery;
import io.pulseboard.core.domain.anomaly.AnomalyRepository;
import io.pulseboard.core.domain.anomaly.Detector;
import io.pulseboard.core.domain.anomaly.Severity;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
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
 * JDBC implementation of {@link AnomalyRepository}. Every statement filters on {@code tenant_id}, and the metric
 * join repeats the tenant so the service of a metric is only resolved inside the tenant. User supplied values are
 * always bound parameters; only fixed SQL fragments are concatenated.
 */
@Repository
public class JdbcAnomalyRepository implements AnomalyRepository {

    private static final String SELECT = """
            SELECT a.id, a.tenant_id, a.metric_id, m.service_id, m.name AS metric_name,
                   s.name AS service_name, a.window_start, a.window_end, a.score,
                   a.detector, a.severity, a.details::text AS details, a.created_at, a.acknowledged_by,
                   a.acknowledged_at
              FROM anomaly a
              JOIN metric_definition m ON m.id = a.metric_id AND m.tenant_id = a.tenant_id
              JOIN service s ON s.id = m.service_id AND s.tenant_id = m.tenant_id
            """;
    private static final String COUNT = """
            SELECT count(*)
              FROM anomaly a
              JOIN metric_definition m ON m.id = a.metric_id AND m.tenant_id = a.tenant_id
            """;
    private static final String ORDER = " ORDER BY a.window_start DESC, a.id DESC";
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final RowMapper<Anomaly> rowMapper = this::mapRow;

    public JdbcAnomalyRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Anomaly> findByIdAndTenantId(UUID id, TenantId tenantId) {
        return jdbcTemplate.query(SELECT + " WHERE a.tenant_id = ? AND a.id = ?", rowMapper, tenantId.value(), id)
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Anomaly> findAllByTenantIdAndIdIn(TenantId tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(SELECT + " WHERE a.tenant_id = ? AND a.id = ANY (?)" + ORDER, rowMapper,
                tenantId.value(), ids.toArray(UUID[]::new));
    }

    @Override
    @Transactional(readOnly = true)
    public AnomalyPage search(TenantId tenantId, AnomalyQuery query, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE a.tenant_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId.value());
        if (query.metricId() != null) {
            where.append(" AND a.metric_id = ?");
            arguments.add(query.metricId());
        }
        if (query.serviceId() != null) {
            where.append(" AND m.service_id = ?");
            arguments.add(query.serviceId());
        }
        if (query.severity() != null) {
            where.append(" AND a.severity = ?");
            arguments.add(query.severity().name());
        }
        if (query.acknowledged() != null) {
            where.append(query.acknowledged() ? " AND a.acknowledged_at IS NOT NULL"
                    : " AND a.acknowledged_at IS NULL");
        }
        if (query.from() != null) {
            where.append(" AND a.window_start >= ?");
            arguments.add(utc(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND a.window_start < ?");
            arguments.add(utc(query.to()));
        }

        Long total = jdbcTemplate.queryForObject(COUNT + where, Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        List<Anomaly> items = jdbcTemplate.query(SELECT + where + ORDER + " LIMIT ? OFFSET ?", rowMapper,
                pageArguments.toArray());
        return new AnomalyPage(items, total == null ? 0 : total);
    }

    @Override
    @Transactional
    public int acknowledge(UUID id, TenantId tenantId, UUID userId, Instant at) {
        return jdbcTemplate.update("""
                UPDATE anomaly
                   SET acknowledged_by = ?, acknowledged_at = ?
                 WHERE id = ? AND tenant_id = ? AND acknowledged_at IS NULL
                """, userId, utc(at), id, tenantId.value());
    }

    private Anomaly mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime acknowledgedAt = rs.getObject("acknowledged_at", OffsetDateTime.class);
        return new Anomaly(
                rs.getObject("id", UUID.class),
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("metric_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                rs.getString("metric_name"),
                rs.getString("service_name"),
                rs.getObject("window_start", OffsetDateTime.class).toInstant(),
                rs.getObject("window_end", OffsetDateTime.class).toInstant(),
                rs.getDouble("score"),
                Detector.valueOf(rs.getString("detector")),
                Severity.valueOf(rs.getString("severity")),
                parseDetails(rs.getString("details")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("acknowledged_by", UUID.class),
                acknowledgedAt == null ? null : acknowledgedAt.toInstant());
    }

    private Map<String, Object> parseDetails(String json) {
        try {
            return jsonMapper.readValue(json, OBJECT);
        } catch (JacksonException ex) {
            throw new IllegalStateException("anomaly.details is not a JSON object", ex);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
