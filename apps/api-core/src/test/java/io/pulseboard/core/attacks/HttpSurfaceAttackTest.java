package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Path normalisation tricks, CORS, security headers, actuator exposure and error-message leaks, as seen by an
 * anonymous client and by an authenticated tenant user.
 */
class HttpSurfaceAttackTest extends AttackTestSupport {

    private TokenResponse tenant;
    private ServiceResponse service;

    @BeforeEach
    void setUp() {
        tenant = registerTenant();
        service = createService(tenant.accessToken(), "surface-svc");
    }

    @Test
    void pathVariantsNeverBypassAuthentication() {
        List<String> variants = List.of(
                "/api/v1/services/",
                "/API/V1/SERVICES",
                "/api/v1/Services",
                "/api/v1/services/" + service.id() + "/metrics/",
                "/api/v1/api-keys/",
                "/api/v1/auth/login/",
                "/api/v1/auth/LOGIN",
                "/api/v1/services.json",
                "/api/v1/services/..;/services");
        for (String path : variants) {
            HttpResponse<String> response = raw("GET", path, null);
            assertThat(response.statusCode()).as(path).isIn(400, 401);
            assertThat(response.body()).as(path).doesNotContain("surface-svc");
        }
    }

    @Test
    void encodedAndDoubledSeparatorsAreRejectedByTheFirewall() {
        List<String> variants = List.of(
                "//api/v1/services",
                "/api//v1/services",
                "/api/v1/%2e%2e/v1/services",
                "/api/v1/services/%2e%2e/services",
                "/api/v1/services/%2F" + service.id() + "/metrics",
                "/api/v1/services;jsessionid=x",
                "/api/v1/services/%252e%252e/services",
                "/actuator/health/..%2f..%2fapi/v1/services");
        for (String path : variants) {
            HttpResponse<String> anonymous = raw("GET", path, null);
            HttpResponse<String> authenticated = getWithBearer(path, tenant.accessToken());
            assertThat(anonymous.statusCode()).as(path).isIn(400, 401, 404);
            assertThat(authenticated.statusCode()).as(path).isIn(400, 401, 404);
            assertThat(anonymous.body()).as(path).doesNotContain("surface-svc");
            assertThat(authenticated.body()).as(path).doesNotContain("surface-svc");
        }
    }

    @Test
    void publicMatchersDoNotOpenNeighbouringPaths() {
        assertThat(raw("GET", "/actuator/health/../../api/v1/services", null).body()).doesNotContain("surface-svc");
        assertThat(raw("GET", "/actuator/env", null).statusCode()).isEqualTo(401);
        assertThat(raw("GET", "/actuator", null).statusCode()).isEqualTo(401);
        assertThat(raw("GET", "/actuator/heapdump", null).statusCode()).isEqualTo(401);
        assertThat(raw("GET", "/actuator/env", null, "Authorization", "Bearer " + tenant.accessToken())
                .statusCode()).isEqualTo(404);
        assertThat(raw("GET", "/actuator/heapdump", null, "Authorization", "Bearer " + tenant.accessToken())
                .statusCode()).isEqualTo(404);
        assertThat(raw("GET", "/actuator/loggers", null, "Authorization", "Bearer " + tenant.accessToken())
                .statusCode()).isEqualTo(404);
    }

    @Test
    void healthAndInfoRevealNoDetails() {
        HttpResponse<String> health = rawOnManagementPort("GET", "/actuator/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).doesNotContain("components").doesNotContain("postgres")
                .doesNotContain("redis").doesNotContain("details");
        assertThat(rawOnManagementPort("GET", "/actuator/info").body()).isEqualTo("{}");
    }

    /**
     * Regression for finding prometheus-endpoint-public: actuator endpoints are served only on the separate,
     * unpublished management port, so the application port must not return metrics to anyone.
     */
    @Test
    void prometheusEndpointIsNotPublic() {
        HttpResponse<String> response = raw("GET", "/actuator/prometheus", null);

        assertThat(response.statusCode()).as(response.body().lines().findFirst().orElse("")).isIn(401, 403, 404);
    }

    @Test
    void crossOriginRequestsGetNoCorsGrant() {
        HttpResponse<String> preflight = raw("OPTIONS", "/api/v1/services", null,
                "Origin", "https://evil.example", "Access-Control-Request-Method", "GET",
                "Access-Control-Request-Headers", "authorization");
        HttpResponse<String> simple = raw("GET", "/api/v1/services", null, "Origin", "https://evil.example",
                "Authorization", "Bearer " + tenant.accessToken());
        HttpResponse<String> nullOrigin = raw("POST", "/api/v1/auth/login",
                loginBody("x-slug", "a@example.com", "irrelevant-password"), "Origin", "null");

        for (HttpResponse<String> response : List.of(preflight, simple, nullOrigin)) {
            assertThat(header(response, "Access-Control-Allow-Origin")).isNull();
            assertThat(header(response, "Access-Control-Allow-Credentials")).isNull();
        }
    }

    @Test
    void securityHeadersArePresentOnApiResponses() {
        for (HttpResponse<String> response : List.of(
                raw("GET", "/api/v1/services", null),
                getWithBearer("/api/v1/services", tenant.accessToken()),
                raw("POST", "/api/v1/auth/login", loginBody("x-slug", "a@example.com", "irrelevant-password")))) {
            assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(header(response, "X-Frame-Options")).isEqualTo("DENY");
            assertThat(header(response, "Cache-Control")).contains("no-store");
            assertThat(header(response, "Set-Cookie")).isNull();
        }
    }

    @Test
    void malformedBodiesGiveGenericProblemDetails() {
        String bearer = "Bearer " + tenant.accessToken();
        List<HttpResponse<String>> responses = List.of(
                raw("POST", "/api/v1/services", "{not json", "Authorization", bearer),
                raw("POST", "/api/v1/services", "[]", "Authorization", bearer),
                raw("POST", "/api/v1/services", "{\"name\":\"x\",\"environment\":\"NOPE\"}", "Authorization", bearer),
                raw("POST", "/api/v1/services", "{\"name\":{\"$gt\":\"\"},\"environment\":\"PROD\"}",
                        "Authorization", bearer),
                raw("POST", "/api/v1/auth/login", "{\"tenantSlug\":1e999999999}"),
                raw("POST", "/api/v1/auth/register", "\"" + "[".repeat(5000)));
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(header(response, "Content-Type")).startsWith(PROBLEM_JSON);
            assertThat(response.body()).doesNotContain("Exception").doesNotContain("at io.")
                    .doesNotContain("jackson").doesNotContain("org.springframework").doesNotContain("SQL");
        }
    }

    @Test
    void typeMismatchesAndUnsupportedMediaTypesAreClientErrors() {
        String bearer = "Bearer " + tenant.accessToken();
        assertThat(raw("GET", "/api/v1/services/not-a-uuid/metrics", null, "Authorization", bearer).statusCode())
                .isEqualTo(400);
        assertThat(raw("GET", "/api/v1/services/' OR 1=1--/metrics".replace(" ", "%20"), null,
                "Authorization", bearer).statusCode()).isEqualTo(400);
        assertThat(raw("POST", "/api/v1/auth/login", "tenantSlug=x", "Content-Type",
                "application/x-www-form-urlencoded").statusCode()).isEqualTo(415);
        assertThat(raw("PUT", "/api/v1/services", "{}", "Authorization", bearer).statusCode()).isEqualTo(405);
    }

    @Test
    void oversizedFieldsAreRejectedByValidation() {
        String bearer = "Bearer " + tenant.accessToken();
        HttpResponse<String> response = raw("POST", "/api/v1/services",
                "{\"name\":\"" + "a".repeat(1_000_000) + "\",\"environment\":\"PROD\"}", "Authorization", bearer);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body().length()).isLessThan(2_000);
    }

    @Test
    void duplicateResourcesAreConflictsWithoutInternals() {
        String bearer = "Bearer " + tenant.accessToken();
        HttpResponse<String> duplicate = raw("POST", "/api/v1/services",
                "{\"name\":\"surface-svc\",\"environment\":\"PROD\"}", "Authorization", bearer);
        HttpResponse<String> padded = raw("POST", "/api/v1/services",
                "{\"name\":\"  surface-svc  \",\"environment\":\"DEV\"}", "Authorization", bearer);

        for (HttpResponse<String> response : List.of(duplicate, padded)) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).doesNotContain("constraint").doesNotContain("service_");
        }
    }

    @Test
    void registerConflictDoesNotLeakDatabaseDetails() {
        String slug = randomSlug();
        register(slug, "owner@example.com");

        HttpResponse<String> response = raw("POST", "/api/v1/auth/register", "{\"tenantSlug\":\""
                + slug.toUpperCase(Locale.ROOT) + "\",\"tenantName\":\"x\",\"email\":\"a@example.com\",\"password\":\""
                + PASSWORD + "\"}");

        assertThat(response.statusCode()).isIn(400, 409);
        assertThat(response.body()).doesNotContain("constraint").doesNotContain("tenant_slug");
    }
}
