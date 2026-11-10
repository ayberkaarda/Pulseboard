package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.MetricDefinitionResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.domain.catalog.Aggregation;
import io.pulseboard.core.domain.catalog.Environment;
import io.pulseboard.core.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class ServiceCatalogIntegrationTest extends IntegrationTest {

    private static final ParameterizedTypeReference<List<ServiceResponse>> SERVICE_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<MetricDefinitionResponse>> METRIC_LIST =
            new ParameterizedTypeReference<>() {
            };

    @Test
    void createAndListServicesOrderedByName() {
        TokenResponse owner = registerTenant();

        ServiceResponse payments = createService(owner.accessToken(), "payments");
        ServiceResponse checkout = createService(owner.accessToken(), "checkout");

        assertThat(payments.environment()).isEqualTo(Environment.PROD);
        assertThat(payments.createdAt()).isNotNull();
        List<ServiceResponse> services = listServices(owner.accessToken());
        assertThat(services).extracting(ServiceResponse::id).containsExactly(checkout.id(), payments.id());
    }

    @Test
    void serviceDetailIsTheListItemAndUnknownIdsAreProblemDetails() {
        TokenResponse owner = registerTenant();
        ServiceResponse payments = createService(owner.accessToken(), "payments");

        client.get().uri("/api/v1/services/{id}", payments.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceResponse.class)
                .isEqualTo(listServices(owner.accessToken()).get(0));
        assertThat(listServices(owner.accessToken())).extracting(ServiceResponse::id).containsExactly(payments.id());
        client.get().uri("/api/v1/services/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        client.get().uri("/api/v1/services/not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isBadRequest();
        client.get().uri("/api/v1/services/{id}", payments.id()).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void duplicateServiceNameIsConflict() {
        TokenResponse owner = registerTenant();
        createService(owner.accessToken(), "checkout");

        client.post().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "checkout", "environment", "DEV"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void serviceValidationErrorsAreProblemDetails() {
        TokenResponse owner = registerTenant();

        client.post().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", ""))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(2);
        client.post().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "x", "environment", "QA"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void createAndListMetricsOfAService() {
        TokenResponse owner = registerTenant();
        ServiceResponse service = createService(owner.accessToken(), "checkout");

        MetricDefinitionResponse latency = createMetric(owner.accessToken(), service.id(), "latency_p95_ms");
        MetricDefinitionResponse errors = createMetric(owner.accessToken(), service.id(), "error_rate");

        assertThat(latency.serviceId()).isEqualTo(service.id());
        assertThat(latency.aggregation()).isEqualTo(Aggregation.P95);
        assertThat(latency.unit()).isEqualTo("ms");
        List<MetricDefinitionResponse> metrics = client.get().uri("/api/v1/services/{id}/metrics", service.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(METRIC_LIST)
                .returnResult()
                .getResponseBody();
        assertThat(metrics).extracting(MetricDefinitionResponse::id).containsExactly(errors.id(), latency.id());
    }

    @Test
    void duplicateMetricNameIsConflictAndInvalidNameIsRejected() {
        TokenResponse owner = registerTenant();
        ServiceResponse service = createService(owner.accessToken(), "checkout");
        createMetric(owner.accessToken(), service.id(), "latency_p95_ms");

        client.post().uri("/api/v1/services/{id}/metrics", service.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "latency_p95_ms", "unit", "ms", "aggregation", "AVG"))
                .exchange()
                .expectStatus().isEqualTo(409);
        client.post().uri("/api/v1/services/{id}/metrics", service.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Latency P95", "unit", "ms", "aggregation", "AVG"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("name");
    }

    @Test
    void metricsOfUnknownServiceAreNotFound() {
        TokenResponse owner = registerTenant();

        client.get().uri("/api/v1/services/{id}/metrics", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:not-found");
        client.get().uri("/api/v1/services/not-a-uuid/metrics")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void catalogRequiresAuthentication() {
        client.get().uri("/api/v1/services").exchange().expectStatus().isUnauthorized();
    }

    private List<ServiceResponse> listServices(String accessToken) {
        return client.get().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SERVICE_LIST)
                .returnResult()
                .getResponseBody();
    }
}
