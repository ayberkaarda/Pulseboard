package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Extra, server-owned fields (tenantId, role, plan, id, key, revokedAt ...) in request bodies must be ignored;
 * the server decides ownership from the authenticated principal only.
 */
class MassAssignmentAttackTest extends AttackTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TokenResponse victim;
    private TokenResponse attacker;

    @BeforeEach
    void setUp() {
        victim = registerTenant();
        attacker = registerTenant();
    }

    @Test
    void registerIgnoresRoleTenantAndPlanFields() {
        String slug = randomSlug();
        UUID forcedId = UUID.randomUUID();
        HttpResponse<String> response = raw("POST", "/api/v1/auth/register", "{\"tenantSlug\":\"" + slug
                + "\",\"tenantName\":\"x\",\"email\":\"ma@example.com\",\"password\":\"" + PASSWORD + "\","
                + "\"role\":\"ADMIN\",\"plan\":\"PRO\",\"tenantId\":\"" + victim.tenantId() + "\","
                + "\"userId\":\"" + forcedId + "\",\"id\":\"" + forcedId + "\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT t.id AS tenant_id, t.plan, u.id AS user_id, "
                + "u.role FROM tenant t JOIN app_user u ON u.tenant_id = t.id WHERE t.slug = ?", slug);
        assertThat(row.get("plan")).isEqualTo("FREE");
        assertThat(row.get("role")).isEqualTo("OWNER");
        assertThat(row.get("tenant_id")).isNotEqualTo(victim.tenantId());
        assertThat(row.get("user_id")).isNotEqualTo(forcedId);
        Integer victimUsers = jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE tenant_id = ?",
                Integer.class, victim.tenantId());
        assertThat(victimUsers).isEqualTo(1);
    }

    @Test
    void createServiceIgnoresTenantIdAndId() {
        UUID forcedId = UUID.randomUUID();
        HttpResponse<String> response = raw("POST", "/api/v1/services", "{\"name\":\"planted\","
                + "\"environment\":\"PROD\",\"tenantId\":\"" + victim.tenantId() + "\",\"tenant_id\":\""
                + victim.tenantId() + "\",\"id\":\"" + forcedId + "\",\"createdAt\":\"2000-01-01T00:00:00Z\"}",
                "Authorization", "Bearer " + attacker.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).doesNotContain(forcedId.toString()).doesNotContain("2000-01-01");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM service WHERE tenant_id = ?", Integer.class,
                victim.tenantId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM service WHERE tenant_id = ? AND name = ?",
                Integer.class, attacker.tenantId(), "planted")).isEqualTo(1);
    }

    @Test
    void createMetricIgnoresServiceIdAndTenantIdInBody() {
        ServiceResponse victimService = createService(victim.accessToken(), "victim-svc");
        ServiceResponse attackerService = createService(attacker.accessToken(), "attacker-svc");

        HttpResponse<String> response = raw("POST", "/api/v1/services/" + attackerService.id() + "/metrics",
                "{\"name\":\"planted_metric\",\"unit\":\"ms\",\"aggregation\":\"AVG\",\"serviceId\":\""
                        + victimService.id() + "\",\"tenantId\":\"" + victim.tenantId() + "\"}",
                "Authorization", "Bearer " + attacker.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains(attackerService.id().toString())
                .doesNotContain(victimService.id().toString());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM metric_definition WHERE service_id = ?",
                Integer.class, victimService.id())).isZero();
    }

    @Test
    void createApiKeyIgnoresKeyPrefixTenantAndRevocationFields() {
        String chosenKey = "pb_" + "A".repeat(43);
        HttpResponse<String> response = raw("POST", "/api/v1/api-keys", "{\"name\":\"planted\",\"key\":\""
                + chosenKey + "\",\"prefix\":\"AAAAAAAA\",\"tenantId\":\"" + victim.tenantId()
                + "\",\"revokedAt\":null,\"keyHash\":\"" + "0".repeat(64) + "\"}",
                "Authorization", "Bearer " + attacker.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).doesNotContain(chosenKey).doesNotContain("\"prefix\":\"AAAAAAAA\"");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM api_key WHERE tenant_id = ?", Integer.class,
                victim.tenantId())).isZero();
        assertThat(raw("GET", "/api/v1/services", null, API_KEY_HEADER, chosenKey).statusCode()).isEqualTo(401);
    }
}
