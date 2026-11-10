package io.pulseboard.core.support;

import io.pulseboard.core.TestcontainersConfiguration;
import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.MetricDefinitionResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base class for HTTP-level integration tests against the full application on a random port, backed by
 * PostgreSQL and Redis containers. All subclasses share one Spring context (and one set of containers); tests
 * isolate their data by registering a fresh tenant with a random slug.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String PASSWORD = "correct-horse-battery-staple";
    protected static final String API_KEY_HEADER = "X-Api-Key";

    @Autowired
    protected RestTestClient client;

    protected static String randomSlug() {
        return "t-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    protected TokenResponse register(String slug, String email) {
        return client.post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", slug, "tenantName", "Tenant " + slug, "email", email,
                        "password", PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected TokenResponse registerTenant() {
        return register(randomSlug(), "owner@example.com");
    }

    protected ServiceResponse createService(String accessToken, String name) {
        return client.post().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "environment", "PROD"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ServiceResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected MetricDefinitionResponse createMetric(String accessToken, UUID serviceId, String name) {
        return client.post().uri("/api/v1/services/{id}/metrics", serviceId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "unit", "ms", "aggregation", "P95"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(MetricDefinitionResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected ApiKeyCreatedResponse issueApiKey(String accessToken, String name) {
        return client.post().uri("/api/v1/api-keys")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ApiKeyCreatedResponse.class)
                .returnResult()
                .getResponseBody();
    }
}
