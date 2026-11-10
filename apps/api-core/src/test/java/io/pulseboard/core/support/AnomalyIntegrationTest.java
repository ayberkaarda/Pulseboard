package io.pulseboard.core.support;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.infrastructure.security.InternalApiProperties;
import io.pulseboard.core.infrastructure.security.InternalSignatureFilter;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Base class of the anomaly tests: tenants with a metric, anomaly rows inserted the way the analytics service does
 * (directly into the table), users of every role, and signed internal calls.
 */
public abstract class AnomalyIntegrationTest extends IntegrationTest {

    protected static final Duration PATIENCE = Duration.ofSeconds(15);

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected InternalApiProperties internalProperties;

    @Autowired
    protected StringRedisTemplate redis;

    @Value("${local.server.port}")
    protected int port;

    /** A registered tenant (its owner signed in) with one service and one metric. */
    public record TenantFixture(String slug, TokenResponse owner, UUID serviceId, UUID metricId) {

        public UUID tenantId() {
            return owner.tenantId();
        }

        public String ownerToken() {
            return owner.accessToken();
        }
    }

    /** An anomaly row that was inserted. */
    public record StoredAnomaly(UUID id, UUID tenantId, UUID metricId, Instant windowStart, String severity) {
    }

    protected TenantFixture newTenant() {
        String slug = randomSlug();
        TokenResponse owner = register(slug, "owner@example.com");
        UUID serviceId = createService(owner.accessToken(), "checkout").id();
        UUID metricId = createMetric(owner.accessToken(), serviceId, "latency_p95_ms").id();
        return new TenantFixture(slug, owner, serviceId, metricId);
    }

    protected UUID newMetric(TenantFixture tenant, String serviceName, String metricName) {
        UUID serviceId = createService(tenant.ownerToken(), serviceName).id();
        return createMetric(tenant.ownerToken(), serviceId, metricName).id();
    }

    protected UUID serviceOf(UUID metricId) {
        return jdbc.queryForObject("SELECT service_id FROM metric_definition WHERE id = ?", UUID.class, metricId);
    }

    protected TokenResponse signInAs(TenantFixture tenant, String email, String role) {
        jdbc.update("""
                INSERT INTO app_user (id, tenant_id, email, password_hash, role, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenant.tenantId(), email, passwordEncoder.encode(PASSWORD), role,
                Timestamp.from(Instant.now()));
        return client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", tenant.slug(), "email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected StoredAnomaly insertAnomaly(TenantFixture tenant, Instant windowStart, String severity) {
        return insertAnomaly(tenant.tenantId(), tenant.metricId(), windowStart, severity);
    }

    protected StoredAnomaly insertAnomaly(UUID tenantId, UUID metricId, Instant windowStart, String severity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO anomaly (id, tenant_id, metric_id, window_start, window_end, score, detector, severity,
                                     details)
                VALUES (?, ?, ?, ?, ?, 0.9, 'ZSCORE', ?, '{"z": 4.2}'::jsonb)
                """, id, tenantId, metricId, Timestamp.from(windowStart), Timestamp.from(windowStart.plusSeconds(300)),
                severity);
        return new StoredAnomaly(id, tenantId, metricId, windowStart, severity);
    }

    /** A notification body for the given rows; the severity in it is deliberately configurable. */
    protected static String notificationBody(String claimedSeverity, StoredAnomaly... anomalies) {
        StringBuilder body = new StringBuilder("{\"anomalies\":[");
        for (int i = 0; i < anomalies.length; i++) {
            StoredAnomaly a = anomalies[i];
            body.append(i == 0 ? "" : ",").append("{\"id\":\"").append(a.id())
                    .append("\",\"tenantId\":\"").append(a.tenantId())
                    .append("\",\"metricId\":\"").append(a.metricId())
                    .append("\",\"windowStart\":\"").append(a.windowStart())
                    .append("\",\"windowEnd\":\"").append(a.windowStart().plusSeconds(300))
                    .append("\",\"score\":0.9,\"detector\":\"ZSCORE\",\"severity\":\"").append(claimedSeverity)
                    .append("\",\"createdAt\":\"").append(Instant.now()).append("\"}");
        }
        return body.append("]}").toString();
    }

    protected static String notificationBody(StoredAnomaly... anomalies) {
        return notificationBody("HIGH", anomalies);
    }

    protected String sign(String body) {
        return hmacHex(internalProperties.sharedSecret(), body);
    }

    protected static String hmacHex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** POSTs the body to the notify endpoint with a correct signature. */
    protected HttpResponse<String> notifySigned(String body) {
        return notifyWith(body, sign(body));
    }

    protected HttpResponse<String> notifyWith(String body, String signature) {
        return RawHttp.postJson(port, "/internal/v1/anomalies/notify", body,
                InternalSignatureFilter.SIGNATURE_HEADER, signature);
    }

    /** Number of Redis subscribers of the channel (PUBSUB NUMSUB). */
    protected long subscribers(String channel) {
        Long count = redis.execute(new DefaultRedisScript<>(
                "return redis.call('PUBSUB', 'NUMSUB', ARGV[1])[2]", Long.class), List.of(), channel);
        return count == null ? 0 : count;
    }

    protected static String channelOf(UUID tenantId) {
        return "anomalies:" + tenantId;
    }
}
