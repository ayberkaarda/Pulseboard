package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Tenant B (attacker) addresses tenant A's (victim) resources by id on every endpoint, with a JWT and with an
 * API key. Every attempt must be a 404 indistinguishable from a random id, and must not change A's data.
 */
class CrossTenantIdorAttackTest extends AttackTestSupport {

    private static final String METRICS = "/api/v1/services/%s/metrics";
    private static final String METRIC_BODY = "{\"name\":\"idor_metric\",\"unit\":\"ms\",\"aggregation\":\"AVG\"}";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private TokenResponse victim;
    private TokenResponse attacker;
    private ServiceResponse victimService;
    private ApiKeyCreatedResponse victimKey;
    private ApiKeyCreatedResponse attackerKey;

    @BeforeEach
    void setUp() {
        victim = registerTenant();
        attacker = registerTenant();
        victimService = createService(victim.accessToken(), "victim-svc");
        createMetric(victim.accessToken(), victimService.id(), "victim_metric");
        victimKey = issueApiKey(victim.accessToken(), "victim-key");
        attackerKey = issueApiKey(attacker.accessToken(), "attacker-key");
    }

    @Test
    void foreignServiceMetricsAreIndistinguishableFromMissingWithJwt() {
        HttpResponse<String> foreign = withJwt("GET", METRICS.formatted(victimService.id()), null);
        UUID random = UUID.randomUUID();
        HttpResponse<String> missing = withJwt("GET", METRICS.formatted(random), null);

        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("victim_metric");
        assertThat(foreign.body().replace(victimService.id().toString(), "ID"))
                .isEqualTo(missing.body().replace(random.toString(), "ID"));
    }

    @Test
    void foreignServiceDetailIsIndistinguishableFromMissingAndOwnDetailIsServed() {
        HttpResponse<String> foreign = withJwt("GET", "/api/v1/services/" + victimService.id(), null);
        UUID random = UUID.randomUUID();
        HttpResponse<String> missing = withJwt("GET", "/api/v1/services/" + random, null);

        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("victim-svc");
        assertThat(foreign.headers().firstValue("Content-Type"))
                .isEqualTo(missing.headers().firstValue("Content-Type"));
        assertThat(foreign.body().replace(victimService.id().toString(), "ID"))
                .isEqualTo(missing.body().replace(random.toString(), "ID"));
        assertThat(raw("GET", "/api/v1/services/" + victimService.id(), null, API_KEY_HEADER, attackerKey.key())
                .statusCode()).isEqualTo(404);

        HttpResponse<String> own = raw("GET", "/api/v1/services/" + victimService.id(), null, "Authorization",
                "Bearer " + victim.accessToken());
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.body()).contains("victim-svc").contains(victimService.id().toString());
    }

    @Test
    void foreignServiceMetricsAreNotFoundWithApiKey() {
        HttpResponse<String> response = raw("GET", METRICS.formatted(victimService.id()), null,
                API_KEY_HEADER, attackerKey.key());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("victim_metric");
    }

    @Test
    void cannotCreateMetricOnForeignServiceIncludingDuplicateNameProbe() {
        assertThat(withJwt("POST", METRICS.formatted(victimService.id()), METRIC_BODY).statusCode()).isEqualTo(404);
        // A name that already exists in the victim's service must not turn into a 409 oracle.
        assertThat(withJwt("POST", METRICS.formatted(victimService.id()),
                "{\"name\":\"victim_metric\",\"unit\":\"ms\",\"aggregation\":\"AVG\"}").statusCode())
                .isEqualTo(404);

        Integer metrics = jdbcTemplate.queryForObject("SELECT count(*) FROM metric_definition WHERE service_id = ?",
                Integer.class, victimService.id());
        assertThat(metrics).isEqualTo(1);
    }

    @Test
    void serviceNamesAreScopedPerTenantSoNoConflictOracleExists() {
        // The attacker can create a service with the victim's service name: no cross-tenant 409.
        assertThat(withJwt("POST", "/api/v1/services", "{\"name\":\"victim-svc\",\"environment\":\"PROD\"}")
                .statusCode()).isEqualTo(201);
    }

    @Test
    void cannotRevokeForeignApiKeyAndForeignKeyKeepsWorking() {
        HttpResponse<String> foreign = withJwt("DELETE", "/api/v1/api-keys/" + victimKey.id(), null);
        UUID random = UUID.randomUUID();
        HttpResponse<String> missing = withJwt("DELETE", "/api/v1/api-keys/" + random, null);

        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body().replace(victimKey.id().toString(), "ID"))
                .isEqualTo(missing.body().replace(random.toString(), "ID"));
        assertThat(raw("GET", "/api/v1/services", null, API_KEY_HEADER, victimKey.key()).statusCode())
                .isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT revoked_at IS NULL FROM api_key WHERE id = ?",
                Boolean.class, victimKey.id())).isTrue();
    }

    @Test
    void apiKeyListsOnlyItsOwnTenant() {
        HttpResponse<String> response = raw("GET", "/api/v1/services", null, API_KEY_HEADER, attackerKey.key());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }

    @Test
    void apiKeyCannotWriteOrManageKeys() {
        assertThat(raw("POST", "/api/v1/services", "{\"name\":\"k\",\"environment\":\"PROD\"}",
                API_KEY_HEADER, attackerKey.key()).statusCode()).isEqualTo(403);
        assertThat(raw("POST", METRICS.formatted(victimService.id()), METRIC_BODY,
                API_KEY_HEADER, attackerKey.key()).statusCode()).isEqualTo(403);
        assertThat(raw("POST", "/api/v1/api-keys", "{\"name\":\"k\"}",
                API_KEY_HEADER, attackerKey.key()).statusCode()).isEqualTo(403);
        assertThat(raw("DELETE", "/api/v1/api-keys/" + attackerKey.id(), null,
                API_KEY_HEADER, attackerKey.key()).statusCode()).isEqualTo(403);
    }

    @Test
    void viewerCannotWriteOrManageKeys() {
        String slug = jdbcTemplate.queryForObject("SELECT slug FROM tenant WHERE id = ?", String.class,
                victim.tenantId());
        jdbcTemplate.update("INSERT INTO app_user (id, tenant_id, email, password_hash, role) "
                + "VALUES (?, ?, 'viewer@example.com', ?, 'VIEWER')", UUID.randomUUID(), victim.tenantId(),
                passwordEncoder.encode(PASSWORD));
        HttpResponse<String> login = login(slug, "viewer@example.com", PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        String viewerToken = login.body().replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        String bearer = "Bearer " + viewerToken;

        assertThat(raw("GET", "/api/v1/services", null, "Authorization", bearer).statusCode()).isEqualTo(200);
        assertThat(raw("POST", "/api/v1/services", "{\"name\":\"v\",\"environment\":\"PROD\"}",
                "Authorization", bearer).statusCode()).isEqualTo(403);
        assertThat(raw("POST", METRICS.formatted(victimService.id()), METRIC_BODY,
                "Authorization", bearer).statusCode()).isEqualTo(403);
        assertThat(raw("POST", "/api/v1/api-keys", "{\"name\":\"v\"}", "Authorization", bearer).statusCode())
                .isEqualTo(403);
        assertThat(raw("DELETE", "/api/v1/api-keys/" + victimKey.id(), null, "Authorization", bearer)
                .statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> withJwt(String method, String path, String body) {
        return raw(method, path, body, "Authorization", "Bearer " + attacker.accessToken());
    }
}
