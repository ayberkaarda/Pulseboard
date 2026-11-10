package io.pulseboard.core.infrastructure.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The ingestion endpoint validates against a copy of {@code contracts/events/metric-batch.schema.json} bundled in
 * the jar (the Docker build context is {@code apps/api-core} only). This test fails when the two drift apart.
 */
class MetricBatchSchemaContractTest {

    @Test
    void bundledSchemaIsIdenticalToTheContract() throws IOException {
        Path contract = Path.of(System.getProperty("pulseboard.contracts.metric-batch-schema",
                "../../contracts/events/metric-batch.schema.json"));
        String bundled;
        try (InputStream stream = new ClassPathResource(JsonSchemaMetricBatchParser.SCHEMA_RESOURCE)
                .getInputStream()) {
            bundled = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(contract).exists();
        assertThat(normalize(bundled))
                .as("copy %s to src/main/resources/%s", contract, JsonSchemaMetricBatchParser.SCHEMA_RESOURCE)
                .isEqualTo(normalize(Files.readString(contract, StandardCharsets.UTF_8)));
    }

    @Test
    void contractCapsABatchAtOneThousandPoints() throws IOException {
        String contract = Files.readString(Path.of(System.getProperty("pulseboard.contracts.metric-batch-schema",
                "../../contracts/events/metric-batch.schema.json")), StandardCharsets.UTF_8);
        assertThat(contract).contains("\"maxItems\": 1000").contains("\"additionalProperties\": false");
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").stripTrailing();
    }
}
