package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End-to-end role checks for ADMIN and VIEWER users. There is no user-management endpoint in this phase, so the
 * users are inserted directly and then sign in through the real login endpoint.
 */
class RoleAuthorizationIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String slug;
    private TokenResponse owner;
    private ServiceResponse service;

    @BeforeEach
    void setUpTenant() {
        slug = randomSlug();
        owner = register(slug, "owner@example.com");
        service = createService(owner.accessToken(), "checkout");
    }

    @Test
    void viewerCanReadButNotWrite() {
        TokenResponse viewer = signIn("viewer@example.com", "VIEWER");
        assertThat(viewer.role()).isEqualTo("VIEWER");

        client.get().uri("/api/v1/services").header(HttpHeaders.AUTHORIZATION, bearer(viewer.accessToken()))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/services/{id}/metrics", service.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(viewer.accessToken()))
                .exchange().expectStatus().isOk();
        post(viewer, "/api/v1/services", Map.of("name", "other", "environment", "DEV"))
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        post(viewer, "/api/v1/services/" + service.id() + "/metrics",
                Map.of("name", "latency", "unit", "ms", "aggregation", "AVG"))
                .expectStatus().isForbidden();
        post(viewer, "/api/v1/api-keys", Map.of("name", "viewer-key")).expectStatus().isForbidden();
        ApiKeyCreatedResponse ownerKey = issueApiKey(owner.accessToken(), "owner-key");
        client.delete().uri("/api/v1/api-keys/{id}", ownerKey.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(viewer.accessToken()))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void adminCanManageServicesMetricsAndApiKeys() {
        TokenResponse admin = signIn("admin@example.com", "ADMIN");
        assertThat(admin.role()).isEqualTo("ADMIN");

        post(admin, "/api/v1/services", Map.of("name", "payments", "environment", "STAGING"))
                .expectStatus().isCreated();
        post(admin, "/api/v1/services/" + service.id() + "/metrics",
                Map.of("name", "latency", "unit", "ms", "aggregation", "AVG"))
                .expectStatus().isCreated();
        ApiKeyCreatedResponse key = post(admin, "/api/v1/api-keys", Map.of("name", "admin-key"))
                .expectStatus().isCreated()
                .expectBody(ApiKeyCreatedResponse.class)
                .returnResult()
                .getResponseBody();
        client.delete().uri("/api/v1/api-keys/{id}", key.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(admin.accessToken()))
                .exchange().expectStatus().isNoContent();
    }

    private TokenResponse signIn(String email, String role) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, tenant_id, email, password_hash, role, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), owner.tenantId(), email, passwordEncoder.encode(PASSWORD), role,
                Timestamp.from(Instant.now()));
        return client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", slug, "email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private RestTestClient.ResponseSpec post(TokenResponse user,
            String path, Map<String, String> body) {
        return client.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }
}
