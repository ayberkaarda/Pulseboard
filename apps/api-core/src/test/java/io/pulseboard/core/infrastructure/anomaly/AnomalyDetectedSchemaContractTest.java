package io.pulseboard.core.infrastructure.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.application.anomaly.NotifiedAnomaly;
import io.pulseboard.core.application.common.InvalidRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The notify endpoint validates against a copy of {@code contracts/events/anomaly-detected.schema.json} bundled in
 * the jar (the Docker build context is {@code apps/api-core} only). This test fails when the two drift apart, and
 * checks that the bundled schema really rejects what the contract forbids.
 */
class AnomalyDetectedSchemaContractTest {

    private static final Path CONTRACT = Path.of(System.getProperty("pulseboard.contracts.anomaly-detected-schema",
            "../../contracts/events/anomaly-detected.schema.json"));

    private final JsonSchemaAnomalyNotificationParser parser = new JsonSchemaAnomalyNotificationParser();

    @Test
    void bundledSchemaIsIdenticalToTheContract() throws IOException {
        String bundled;
        try (InputStream stream = new ClassPathResource(JsonSchemaAnomalyNotificationParser.SCHEMA_RESOURCE)
                .getInputStream()) {
            bundled = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(CONTRACT).exists();
        assertThat(normalize(bundled))
                .as("copy %s to src/main/resources/%s", CONTRACT, JsonSchemaAnomalyNotificationParser.SCHEMA_RESOURCE)
                .isEqualTo(normalize(Files.readString(CONTRACT, StandardCharsets.UTF_8)));
    }

    @Test
    void contractIsStrictAndCapsABatchAtFiveHundredEntries() throws IOException {
        String contract = Files.readString(CONTRACT, StandardCharsets.UTF_8);

        assertThat(contract).contains("\"maxItems\": 500").contains("\"minItems\": 1");
        assertThat(contract.split("\"additionalProperties\": false", -1)).hasSize(3);
    }

    @Test
    void aValidEntryIsParsedToItsIdAndClaimedTenant() {
        UUID id = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();

        List<NotifiedAnomaly> parsed = parser.parse(
                entry(id, tenant, "0.5", "\"LOW\"").getBytes(StandardCharsets.UTF_8));

        assertThat(parsed).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(id);
            assertThat(entry.tenantId().value()).isEqualTo(tenant);
        });
    }

    @Test
    void theBundledSchemaRejectsWhatTheContractForbids() {
        UUID id = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        String[] bad = {
            entry(id, tenant, "1.5", "\"LOW\""),
            entry(id, tenant, "-0.1", "\"LOW\""),
            entry(id, tenant, "0.5", "\"CRITICAL\""),
            entry(id, tenant, "0.5", "null"),
            entry(id, tenant, "0.5", "\"LOW\"").replace("\"ZSCORE\"", "\"MAGIC\""),
            entry(id, tenant, "0.5", "\"LOW\"").replace("\"createdAt\"", "\"extra\":1,\"createdAt\""),
            entry(id, tenant, "0.5", "\"LOW\"").replace(id.toString(), "not-a-uuid"),
            entry(id, tenant, "0.5", "\"LOW\"").replace("2026-05-01T10:00:00Z", "yesterday"),
            "{\"anomalies\":[]}",
            "{}",
            "[]",
        };
        for (String body : bad) {
            assertThatThrownBy(() -> parser.parse(body.getBytes(StandardCharsets.UTF_8)))
                    .as(body)
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    private static String entry(UUID id, UUID tenant, String score, String severity) {
        return "{\"anomalies\":[{\"id\":\"" + id + "\",\"tenantId\":\"" + tenant + "\",\"metricId\":\""
                + UUID.randomUUID() + "\",\"windowStart\":\"2026-05-01T10:00:00Z\","
                + "\"windowEnd\":\"2026-05-01T10:05:00Z\",\"score\":" + score + ",\"detector\":\"ZSCORE\","
                + "\"severity\":" + severity + ",\"createdAt\":\"2026-05-01T10:06:00Z\"}]}";
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").stripTrailing();
    }
}
