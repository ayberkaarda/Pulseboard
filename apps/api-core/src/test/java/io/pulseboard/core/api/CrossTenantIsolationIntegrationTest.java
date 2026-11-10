package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Resources of another tenant must be indistinguishable from resources that do not exist: 404, never 403, and
 * never data. Both credential types (JWT and API key) are covered.
 */
class CrossTenantIsolationIntegrationTest extends IntegrationTest {

    private static final ParameterizedTypeReference<List<ServiceResponse>> SERVICE_LIST =
            new ParameterizedTypeReference<>() {
            };

    private TokenResponse alice;
    private TokenResponse mallory;
    private ServiceResponse aliceService;
    private ApiKeyCreatedResponse aliceKey;
    private ApiKeyCreatedResponse malloryKey;

    @BeforeEach
    void setUpTwoTenants() {
        // The same email in both tenants: emails are unique per tenant only.
        alice = registerTenant();
        mallory = registerTenant();
        aliceService = createService(alice.accessToken(), "checkout");
        createMetric(alice.accessToken(), aliceService.id(), "latency_p95_ms");
        aliceKey = issueApiKey(alice.accessToken(), "alice-collector");
        malloryKey = issueApiKey(mallory.accessToken(), "mallory-collector");
    }

    @Test
    void serviceListContainsOnlyOwnServices() {
        ServiceResponse malloryService = createService(mallory.accessToken(), "checkout");

        List<ServiceResponse> visibleToMallory = client.get().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(mallory.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SERVICE_LIST)
                .returnResult()
                .getResponseBody();
        assertThat(visibleToMallory).extracting(ServiceResponse::id).containsExactly(malloryService.id());
    }

    @Test
    void foreignServiceMetricsAreNotFoundWithJwt() {
        client.get().uri("/api/v1/services/{id}/metrics", aliceService.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(mallory.accessToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void foreignServiceMetricsAreNotFoundWithApiKey() {
        client.get().uri("/api/v1/services/{id}/metrics", aliceService.id())
                .header(API_KEY_HEADER, malloryKey.key())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void cannotCreateMetricOnForeignService() {
        client.post().uri("/api/v1/services/{id}/metrics", aliceService.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(mallory.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "injected", "unit", "ms", "aggregation", "AVG"))
                .exchange()
                .expectStatus().isNotFound();

        List<?> aliceMetrics = client.get().uri("/api/v1/services/{id}/metrics", aliceService.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(List.class)
                .returnResult()
                .getResponseBody();
        assertThat(aliceMetrics).hasSize(1);
    }

    @Test
    void cannotRevokeForeignApiKey() {
        client.delete().uri("/api/v1/api-keys/{id}", aliceKey.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(mallory.accessToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        // Alice's key still works.
        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, aliceKey.key())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void apiKeyOnlySeesItsOwnTenant() {
        List<ServiceResponse> visibleToMalloryKey = client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, malloryKey.key())
                .exchange()
                .expectStatus().isOk()
                .expectBody(SERVICE_LIST)
                .returnResult()
                .getResponseBody();
        assertThat(visibleToMalloryKey).isEmpty();
    }
}
