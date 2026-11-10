package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registerCreatesTenantAndOwnerAndReturnsTokenPair() {
        String slug = randomSlug();
        Instant before = Instant.now();

        TokenResponse tokens = register(slug, "Owner@Example.com");

        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.role()).isEqualTo("OWNER");
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(Duration.between(before, tokens.accessTokenExpiresAt()))
                .isBetween(Duration.ofMinutes(14), Duration.ofMinutes(16));
        assertThat(Duration.between(before, tokens.refreshTokenExpiresAt()))
                .isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7).plusMinutes(1));

        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT u.email, u.role, u.password_hash, t.plan FROM app_user u JOIN tenant t ON t.id = u.tenant_id "
                        + "WHERE t.slug = ?", slug);
        assertThat(user.get("email")).isEqualTo("owner@example.com");
        assertThat(user.get("role")).isEqualTo("OWNER");
        assertThat(user.get("plan")).isEqualTo("FREE");
        assertThat((String) user.get("password_hash")).startsWith("$argon2id$");
    }

    @Test
    void refreshTokensAreStoredOnlyAsSha256Hashes() {
        TokenResponse tokens = registerTenant();

        Integer byPlaintext = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE token_hash = ?", Integer.class, tokens.refreshToken());
        Integer byHash = jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_token WHERE token_hash = ?",
                Integer.class, SecureTokens.sha256Hex(tokens.refreshToken()));
        assertThat(byPlaintext).isZero();
        assertThat(byHash).isEqualTo(1);
    }

    @Test
    void registerWithTakenSlugIsConflict() {
        String slug = randomSlug();
        register(slug, "owner@example.com");

        client.post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", slug, "tenantName", "Other", "email", "other@example.com",
                        "password", PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void registerValidatesBodyWithProblemDetails() {
        client.post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", "Not A Slug", "tenantName", "", "email", "not-an-email",
                        "password", "short"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:pulseboard:problem:validation")
                .jsonPath("$.errors.length()").isEqualTo(4);
    }

    @Test
    void loginReturnsTokensUsableAsBearer() {
        String slug = randomSlug();
        register(slug, "owner@example.com");

        TokenResponse tokens = login(slug, "OWNER@example.com", PASSWORD)
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();

        client.get().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokens.accessToken()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void loginWithWrongPasswordOrUnknownTenantIsUnauthorized() {
        String slug = randomSlug();
        register(slug, "owner@example.com");

        login(slug, "owner@example.com", "wrong-password-123")
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid credentials");
        login(randomSlug(), "owner@example.com", PASSWORD)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid credentials");
    }

    @Test
    void refreshRotatesTokensAndRejectsTheOldOne() {
        TokenResponse initial = registerTenant();

        TokenResponse rotated = refresh(initial.refreshToken())
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(rotated.tenantId()).isEqualTo(initial.tenantId());
        assertThat(rotated.userId()).isEqualTo(initial.userId());

        refresh(initial.refreshToken())
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid refresh token");
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        TokenResponse initial = registerTenant();
        TokenResponse rotated = refresh(initial.refreshToken())
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();

        refresh(initial.refreshToken()).expectStatus().isUnauthorized();

        // The successor was valid before the reuse; it must now be revoked as well.
        refresh(rotated.refreshToken()).expectStatus().isUnauthorized();
        Integer active = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Integer.class,
                rotated.userId());
        assertThat(active).isZero();
    }

    @Test
    void refreshWithUnknownTokenIsUnauthorized() {
        refresh(UUID.randomUUID().toString()).expectStatus().isUnauthorized();
    }

    @Test
    void tamperedAccessTokenIsUnauthorized() {
        TokenResponse tokens = registerTenant();
        String token = tokens.accessToken();
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        client.get().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(tampered))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void apiKeyHeaderIsIgnoredOnAuthEndpoints() {
        String slug = randomSlug();
        register(slug, "owner@example.com");
        String bogusKey = "pb_" + SecureTokens.randomUrlSafe(32);

        client.post().uri("/api/v1/auth/login")
                .header(API_KEY_HEADER, bogusKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", slug, "email", "owner@example.com", "password", "wrong-password-1"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid credentials");
        client.post().uri("/api/v1/auth/login")
                .header(API_KEY_HEADER, bogusKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", slug, "email", "owner@example.com", "password", PASSWORD))
                .exchange()
                .expectStatus().isOk();
    }

    private RestTestClient.ResponseSpec login(String slug, String email, String password) {
        return client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", slug, "email", email, "password", password))
                .exchange();
    }

    private RestTestClient.ResponseSpec refresh(String refreshToken) {
        return client.post().uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .exchange();
    }
}
