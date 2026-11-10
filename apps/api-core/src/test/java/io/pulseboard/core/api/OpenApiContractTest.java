package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code contracts/api-core.openapi.yaml} in sync with the springdoc document the application serves.
 *
 * <p>In the regular test run a difference fails the build (contract drift). The Gradle task
 * {@code generateOpenApiDocs} runs this test with {@code pulseboard.contracts.openapi.write=true}, which writes
 * the served document to the contract file instead.
 */
class OpenApiContractTest extends IntegrationTest {

    private static final String CONTRACT_PROPERTY = "pulseboard.contracts.openapi";
    private static final String WRITE_PROPERTY = "pulseboard.contracts.openapi.write";

    @Test
    void checkedInContractMatchesTheServedDocument() throws IOException {
        String served = normalize(client.get().uri("/v3/api-docs.yaml")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody());
        Path contract = Path.of(System.getProperty(CONTRACT_PROPERTY, "../../contracts/api-core.openapi.yaml"));

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.writeString(contract, served, StandardCharsets.UTF_8);
            return;
        }
        assertThat(contract).as("contract file; run ./gradlew generateOpenApiDocs").exists();
        assertThat(normalize(Files.readAllBytes(contract)))
                .as("contracts/api-core.openapi.yaml drifted from the code; run ./gradlew generateOpenApiDocs")
                .isEqualTo(served);
    }

    @Test
    void documentDescribesThePhaseTwoEndpoints() {
        client.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").value(String.class, version -> assertThat(version).startsWith("3.1"))
                .jsonPath("$.servers[0].url").isEqualTo("/")
                .jsonPath("$.paths['/api/v1/ingest'].post.security[0].apiKey").exists()
                .jsonPath("$.paths['/api/v1/ingest'].post.responses['202']").exists()
                .jsonPath("$.paths['/api/v1/ingest'].post.responses['429'].headers['Retry-After']").exists()
                .jsonPath("$.paths['/api/v1/metrics/{metricId}/points'].get.security[0].bearerAuth").exists()
                .jsonPath("$.components.schemas.MetricBatch.properties.points.maxItems").isEqualTo(1000)
                .jsonPath("$.components.securitySchemes.apiKey.name").isEqualTo("X-Api-Key");
    }

    @Test
    void documentDescribesTheAnomalyEndpointsAndKeepsTheInternalOneApart() {
        client.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.version").isEqualTo("0.5.0")
                .jsonPath("$.paths['/api/v1/anomalies'].get.security[0].bearerAuth").exists()
                .jsonPath("$.paths['/api/v1/anomalies'].get.parameters[?(@.name=='size')].schema.maximum")
                .isEqualTo(100)
                .jsonPath("$.paths['/api/v1/anomalies'].get.parameters[?(@.name=='size')].schema.type")
                .isEqualTo("integer")
                .jsonPath("$.paths['/api/v1/anomalies/{id}/ack'].post.responses['404']").exists()
                .jsonPath("$.paths['/api/v1/anomalies/{id}/ack'].post.responses['403']").exists()
                .jsonPath("$.paths['/api/v1/stream/anomalies'].get.responses['200'].content['text/event-stream']")
                .exists()
                .jsonPath("$.paths['/api/v1/stream/anomalies'].get.responses['429'].headers['Retry-After']")
                .exists()
                .jsonPath("$.paths['/api/v1/stream/anomalies'].get.security[0].bearerAuth").exists()
                .jsonPath("$.paths['/api/v1/stream/anomalies'].get.security[1].streamTicket").exists()
                .jsonPath("$.paths['/api/v1/stream/anomalies'].get.parameters[?(@.name=='ticket')].in")
                .isEqualTo("query")
                .jsonPath("$.paths['/api/v1/stream/ticket'].post.security[0].bearerAuth").exists()
                .jsonPath("$.paths['/api/v1/stream/ticket'].post.responses['429'].headers['Retry-After']")
                .exists()
                .jsonPath("$.components.securitySchemes.streamTicket.in").isEqualTo("query")
                .jsonPath("$.paths['/internal/v1/anomalies/notify'].post.tags[0]").isEqualTo("internal")
                .jsonPath("$.paths['/internal/v1/anomalies/notify'].post.security[0].internalHmac").exists()
                .jsonPath("$.paths['/internal/v1/anomalies/notify'].post.security[0].bearerAuth").doesNotExist()
                .jsonPath("$.paths['/internal/v1/anomalies/notify'].post.security[0].apiKey").doesNotExist()
                .jsonPath("$.paths['/internal/v1/anomalies/notify'].post.responses['401']").exists()
                .jsonPath("$.paths['/internal/v1/anomalies/notify'].post.responses['413']").exists()
                .jsonPath("$.components.schemas.AnomalyDetectedNotification.properties.anomalies.maxItems")
                .isEqualTo(500)
                .jsonPath("$.components.schemas.AnomalyDetectedNotification.additionalProperties").isEqualTo(false)
                .jsonPath("$.components.securitySchemes.internalHmac.name").isEqualTo("X-Internal-Signature");
    }

    /** Properties the server always returns are required, so generated clients can rely on them. */
    @Test
    void responseSchemasMarkEveryAlwaysPresentPropertyRequired() {
        Map<String, List<String>> expected = Map.ofEntries(
                Map.entry("TokenResponse", List.of("accessToken", "accessTokenExpiresAt", "refreshToken",
                        "refreshTokenExpiresAt", "role", "tenantId", "tokenType", "userId")),
                Map.entry("ServiceResponse", List.of("createdAt", "environment", "id", "name")),
                Map.entry("MetricDefinitionResponse",
                        List.of("aggregation", "createdAt", "id", "name", "serviceId", "unit")),
                Map.entry("Anomaly", List.of("acknowledgedAt", "acknowledgedBy", "createdAt", "details", "detector",
                        "id", "metricId", "score", "serviceId", "severity", "tenantId", "windowEnd", "windowStart")),
                Map.entry("AnomalyPage", List.of("items", "page", "size", "totalItems", "totalPages")),
                Map.entry("AnomalyNotificationResult", List.of("accepted", "unknown")),
                Map.entry("ApiKeyCreatedResponse", List.of("createdAt", "id", "key", "name", "prefix")),
                Map.entry("MetricPoints", List.of("aggregation", "from", "metricId", "points", "step", "to")),
                Map.entry("MetricPointBucket", List.of("count", "ts", "value")),
                Map.entry("IngestResponse", List.of("accepted", "rejected")),
                Map.entry("RejectedPoint", List.of("index", "reason")),
                Map.entry("StreamTicketResponse", List.of("expiresInSeconds", "ticket")),
                Map.entry("Problem", List.of("status", "title", "type")));
        byte[] body = client.get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        JsonNode schemas = JsonMapper.builder().build().readTree(body).path("components").path("schemas");

        expected.forEach((name, required) -> {
            List<String> actual = new ArrayList<>();
            schemas.path(name).path("required").forEach(node -> actual.add(node.asText()));
            assertThat(actual).as("required members of %s", name).containsExactlyInAnyOrderElementsOf(required);
            // A required member must be declared, and nullable members stay required but are typed as nullable.
            required.forEach(member -> assertThat(schemas.path(name).path("properties").has(member))
                    .as("%s.%s is declared", name, member).isTrue());
        });
        assertThat(schemas.path("Anomaly").path("properties").path("acknowledgedBy").path("type").toString())
                .contains("null");
        assertThat(schemas.path("MetricPointBucket").path("properties").path("value").path("type").toString())
                .contains("null");
        assertThat(schemas.path("ProblemFieldError").has("required")).isFalse();
    }

    @Test
    void documentDescribesLogoutAndTheServiceDetail() {
        client.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths['/api/v1/auth/logout'].post.responses['204']").exists()
                .jsonPath("$.paths['/api/v1/auth/logout'].post.responses['400']").exists()
                .jsonPath("$.paths['/api/v1/auth/logout'].post.responses['429']").exists()
                .jsonPath("$.paths['/api/v1/auth/logout'].post.security").doesNotExist()
                .jsonPath("$.components.schemas.LogoutRequest.required[0]").isEqualTo("refreshToken")
                .jsonPath("$.paths['/api/v1/services/{serviceId}'].get.security[0].bearerAuth").exists()
                .jsonPath("$.paths['/api/v1/services/{serviceId}'].get.responses['404']").exists()
                .jsonPath("$.paths['/api/v1/services/{serviceId}'].get.responses['200'].content"
                        + "['application/json'].schema['$ref']").isEqualTo("#/components/schemas/ServiceResponse");
    }

    /** The document must reject what the server rejects and list the errors the server can answer with. */
    @Test
    void documentIsAsStrictAsTheRuntime() {
        client.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.schemas.MetricBatch.additionalProperties").isEqualTo(false)
                .jsonPath("$.components.schemas.MetricPoint.additionalProperties").isEqualTo(false)
                .jsonPath("$.components.schemas.MetricBatch.required[0]").isEqualTo("points")
                .jsonPath("$.components.schemas.MetricPoint.properties.value.description")
                .value(String.class, description -> assertThat(description).contains("1e100"))
                // Problem extension members are top-level on the wire, so they are top-level in the schema.
                .jsonPath("$.components.schemas.Problem.properties.errors").exists()
                .jsonPath("$.components.schemas.Problem.properties.properties").doesNotExist()
                .jsonPath("$.components.schemas.ProblemDetail").doesNotExist()
                .jsonPath("$.paths['/api/v1/ingest'].post.responses['409']").exists()
                .jsonPath("$.paths['/api/v1/ingest'].post.responses['500']").exists()
                .jsonPath("$.paths['/api/v1/ingest'].post.responses['503']").exists()
                .jsonPath("$.paths['/api/v1/metrics/{metricId}/points'].get.responses['422']").exists()
                .jsonPath("$.paths['/api/v1/metrics/{metricId}/points'].get.responses['429']").exists()
                .jsonPath("$.paths['/api/v1/metrics/{metricId}/points'].get.responses['500']").exists()
                .jsonPath("$.paths['/api/v1/metrics/{metricId}/points'].get.responses['503']").exists()
                .jsonPath("$.paths['/api/v1/metrics/{metricId}/points'].get.description")
                .value(String.class, description -> assertThat(description).contains("366 days"));
    }

    /** UTF-8, LF line endings and exactly one trailing newline, so the file is identical on every platform. */
    private static String normalize(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return text.stripTrailing() + "\n";
    }
}
