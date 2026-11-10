package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.AnomalyIntegrationTest;
import io.pulseboard.core.support.RawHttp;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The two ways in must not mix: the internal endpoint is reachable with the body signature only, and the
 * signature opens nothing outside {@code /internal}.
 */
class InternalEndpointSecurityIntegrationTest extends AnomalyIntegrationTest {

    private static final String NOTIFY = "/internal/v1/anomalies/notify";
    private static final String SIGNATURE = "X-Internal-Signature";

    @Test
    void aJwtIsNotACredentialForTheInternalEndpoint() {
        TenantFixture tenant = newTenant();
        String body = notificationBody(insertAnomaly(tenant, Instant.parse("2026-03-01T00:00:00Z"), "LOW"));

        for (String token : new String[] {tenant.ownerToken(), signInAs(tenant, "admin@example.com", "ADMIN")
                .accessToken()}) {
            HttpResponse<String> response = RawHttp.postJson(port, NOTIFY, body,
                    HttpHeaders.AUTHORIZATION, "Bearer " + token);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
    }

    @Test
    void anApiKeyIsNotACredentialForTheInternalEndpoint() {
        TenantFixture tenant = newTenant();
        String apiKey = issueApiKey(tenant.ownerToken(), "agent").key();
        String body = notificationBody(insertAnomaly(tenant, Instant.parse("2026-03-01T00:00:00Z"), "LOW"));

        HttpResponse<String> response = RawHttp.postJson(port, NOTIFY, body, API_KEY_HEADER, apiKey);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void aValidSignatureIsEnoughWhateverElseTheRequestCarries() {
        TenantFixture tenant = newTenant();
        String body = notificationBody(insertAnomaly(tenant, Instant.parse("2026-03-01T00:00:00Z"), "LOW"));

        // A bad token next to a good signature is ignored: the internal chain does not read bearer tokens.
        HttpResponse<String> response = RawHttp.postJson(port, NOTIFY, body, SIGNATURE, sign(body),
                HttpHeaders.AUTHORIZATION, "Bearer not-a-token");

        assertThat(response.statusCode()).isEqualTo(202);
    }

    @Test
    void theSignatureHeaderAloneOpensNoPublicEndpoint() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, Instant.parse("2026-03-01T00:00:00Z"), "LOW");
        String signature = sign("");

        client.get().uri("/api/v1/anomalies").header(SIGNATURE, signature).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/anomalies/{id}", stored.id()).header(SIGNATURE, signature).exchange()
                .expectStatus().isUnauthorized();
        client.post().uri("/api/v1/anomalies/{id}/ack", stored.id()).header(SIGNATURE, signature).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/stream/anomalies").header(SIGNATURE, signature).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/services").header(SIGNATURE, signature).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/metrics/{id}/points?from=2026-03-01T00:00:00Z&to=2026-03-02T00:00:00Z",
                tenant.metricId()).header(SIGNATURE, signature).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/ingest").header(SIGNATURE, signature).contentType(MediaType.APPLICATION_JSON)
                .body("{\"points\":[]}").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/api-keys").header(SIGNATURE, signature).contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"x\"}").exchange().expectStatus().isUnauthorized();
        assertThat(jdbc.queryForObject("SELECT acknowledged_at IS NULL FROM anomaly WHERE id = ?", Boolean.class,
                stored.id())).isTrue();
    }

    @Test
    void theSignatureOfOneBodyDoesNotAuthenticateAnotherPathOrBody() {
        String body = "{\"anomalies\":[]}";
        String signature = sign(body);

        // Same signature, other body: rejected. Other path under /internal: not found, never an open door.
        assertThat(notifyWith("{\"anomalies\":[] }", signature).statusCode()).isEqualTo(401);
        assertThat(RawHttp.postJson(port, "/internal/v1/anomalies/other", body, SIGNATURE, signature).statusCode())
                .isEqualTo(404);
        // Path spellings that skip the internal chain do not skip the body check of the endpoint behind it.
        assertThat(RawHttp.postJson(port, "/internal/v1/anomalies/notify/", body, SIGNATURE, "0".repeat(64))
                .statusCode()).isEqualTo(401);
        assertThat(RawHttp.postJson(port, "/Internal/v1/anomalies/notify", body, SIGNATURE, signature)
                .statusCode()).isEqualTo(401);
        assertThat(RawHttp.postJson(port, "/internal/v1/anomalies/" + UUID.randomUUID(), body, SIGNATURE,
                signature).statusCode()).isEqualTo(404);
    }
}
