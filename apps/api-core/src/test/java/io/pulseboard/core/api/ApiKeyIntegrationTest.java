package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class ApiKeyIntegrationTest extends IntegrationTest {

    private static final ParameterizedTypeReference<List<ServiceResponse>> SERVICE_LIST =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void issuedKeyIsShownOnceAndStoredAsHash() {
        TokenResponse owner = registerTenant();

        ApiKeyCreatedResponse key = issueApiKey(owner.accessToken(), "collector");

        assertThat(key.key()).startsWith("pb_").hasSize(3 + 43);
        assertThat(key.prefix()).hasSize(8).isEqualTo(key.key().substring(3, 11));
        assertThat(key.name()).isEqualTo("collector");
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT key_hash, prefix FROM api_key WHERE id = ?",
                key.id());
        assertThat(row.get("key_hash")).isEqualTo(SecureTokens.sha256Hex(key.key()));
        assertThat(row.get("prefix")).isEqualTo(key.prefix());
    }

    @Test
    void apiKeyAuthenticatesReadAccessToTheOwningTenant() {
        TokenResponse owner = registerTenant();
        ServiceResponse service = createService(owner.accessToken(), "checkout");
        ApiKeyCreatedResponse key = issueApiKey(owner.accessToken(), "collector");

        List<ServiceResponse> services = client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .exchange()
                .expectStatus().isOk()
                .expectBody(SERVICE_LIST)
                .returnResult()
                .getResponseBody();

        assertThat(services).extracting(ServiceResponse::id).containsExactly(service.id());
        assertThat(jdbcTemplate.queryForObject("SELECT last_used_at IS NOT NULL FROM api_key WHERE id = ?",
                Boolean.class, key.id())).isTrue();
    }

    @Test
    void apiKeyCannotManageResources() {
        TokenResponse owner = registerTenant();
        ApiKeyCreatedResponse key = issueApiKey(owner.accessToken(), "collector");

        client.post().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "sneaky", "environment", "DEV"))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        client.post().uri("/api/v1/api-keys")
                .header(API_KEY_HEADER, key.key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "escalation"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void revokedKeyIsRejected() {
        TokenResponse owner = registerTenant();
        ApiKeyCreatedResponse key = issueApiKey(owner.accessToken(), "collector");

        client.delete().uri("/api/v1/api-keys/{id}", key.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isNoContent();

        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid API key");

        // Revocation is idempotent.
        client.delete().uri("/api/v1/api-keys/{id}", key.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void unknownOrMalformedKeysAreRejected() {
        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, "pb_" + SecureTokens.randomUrlSafe(32))
                .exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, "not-a-key")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void sendingBothCredentialsIsRejected() {
        TokenResponse owner = registerTenant();
        ApiKeyCreatedResponse key = issueApiKey(owner.accessToken(), "collector");

        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void revokingAnUnknownKeyIsNotFound() {
        TokenResponse owner = registerTenant();

        client.delete().uri("/api/v1/api-keys/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void issuingRequiresAName() {
        TokenResponse owner = registerTenant();

        client.post().uri("/api/v1/api-keys")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", " "))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
