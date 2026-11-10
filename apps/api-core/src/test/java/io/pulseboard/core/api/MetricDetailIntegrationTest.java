package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.MetricDefinitionResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.support.AnomalyIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class MetricDetailIntegrationTest extends AnomalyIntegrationTest {

    private static final ParameterizedTypeReference<List<MetricDefinitionResponse>> METRIC_LIST =
            new ParameterizedTypeReference<>() {
            };

    @Test
    void metricDetailIsTheListItemOfItsService() {
        TokenResponse owner = registerTenant();
        ServiceResponse service = createService(owner.accessToken(), "checkout");
        createMetric(owner.accessToken(), service.id(), "error_rate", "AVG");
        MetricDefinitionResponse latency = createMetric(owner.accessToken(), service.id(), "latency_p95_ms");

        MetricDefinitionResponse detail = client.get().uri("/api/v1/metrics/{id}", latency.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(MetricDefinitionResponse.class)
                .returnResult().getResponseBody();

        List<MetricDefinitionResponse> listed = client.get().uri("/api/v1/services/{id}/metrics", service.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange().expectStatus().isOk().expectBody(METRIC_LIST).returnResult().getResponseBody();
        assertThat(detail.id()).isEqualTo(latency.id());
        assertThat(listed).contains(detail);
        assertThat(detail.serviceId()).isEqualTo(service.id());
    }

    @Test
    void unknownAndForeignMetricsAreIndistinguishable() {
        TenantFixture victim = newTenant();
        TenantFixture attacker = newTenant();
        UUID random = UUID.randomUUID();

        String foreign = notFoundBody(attacker.ownerToken(), victim.metricId());
        String missing = notFoundBody(attacker.ownerToken(), random);

        assertThat(foreign).doesNotContain("latency_p95_ms").doesNotContain(victim.serviceId().toString());
        assertThat(foreign.replace(victim.metricId().toString(), "ID"))
                .isEqualTo(missing.replace(random.toString(), "ID"));
    }

    @Test
    void metricDetailNeedsABearerTokenAndRejectsMalformedIds() {
        TenantFixture tenant = newTenant();

        client.get().uri("/api/v1/metrics/{id}", tenant.metricId()).exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/metrics/not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.ownerToken()))
                .exchange().expectStatus().isBadRequest();
        String viewer = signInAs(tenant, "viewer@example.com", "VIEWER").accessToken();
        client.get().uri("/api/v1/metrics/{id}", tenant.metricId())
                .header(HttpHeaders.AUTHORIZATION, bearer(viewer))
                .exchange().expectStatus().isOk();
        String apiKey = issueApiKey(tenant.ownerToken(), "ingest").key();
        client.get().uri("/api/v1/metrics/{id}", tenant.metricId())
                .header(API_KEY_HEADER, apiKey)
                .exchange().expectStatus().isForbidden();
    }

    private String notFoundBody(String token, UUID metricId) {
        return client.get().uri("/api/v1/metrics/{id}", metricId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
    }
}
