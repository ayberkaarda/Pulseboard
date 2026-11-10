package io.pulseboard.core.infrastructure.ingest;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.InvalidRequestException.Violation;
import io.pulseboard.core.application.ingest.MetricBatch;
import io.pulseboard.core.application.ingest.MetricBatchParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MetricBatchParser} that validates against the bundled copy of
 * {@code contracts/events/metric-batch.schema.json} (JSON Schema 2020-12, networknt json-schema-validator).
 *
 * <p>Formats ({@code uuid}, {@code date-time}) are asserted, not merely annotated. The JSON parser rejects
 * duplicate object keys and trailing content, so the validated document is exactly the one that is ingested.
 */
@Component
public class JsonSchemaMetricBatchParser implements MetricBatchParser {

    /** Classpath location of the bundled schema; a test keeps it identical to the contract file. */
    public static final String SCHEMA_RESOURCE = "schemas/metric-batch.schema.json";

    /** At most this many violations are reported, so an error response stays small. */
    static final int MAX_REPORTED_VIOLATIONS = 20;

    // Floats are read as BigDecimal: a literal beyond the double range stays a JSON number for the schema (a double
    // would turn into infinity, which the validator does not treat as a number) and is rejected per point later.
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private final Schema schema;

    public JsonSchemaMetricBatchParser() {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                // Messages are part of the API response; never localise them to the server's default locale.
                .locale(Locale.ENGLISH)
                .pathType(PathType.JSON_POINTER)
                .build();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(config));
        try (InputStream schemaStream = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            this.schema = registry.getSchema(schemaStream);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot load " + SCHEMA_RESOURCE, ex);
        }
    }

    @Override
    public MetricBatch parse(byte[] json) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(json);
        } catch (JacksonException ex) {
            String position = ex.getLocation() == null ? ""
                    : " at line " + ex.getLocation().getLineNr() + ", column " + ex.getLocation().getColumnNr();
            throw new InvalidRequestException("The request body is not valid JSON",
                    List.of(new Violation("", "malformed JSON" + position)));
        }
        if (root == null || root.isMissingNode()) {
            throw new InvalidRequestException("The request body is empty");
        }
        List<Error> errors = schema.validate(root);
        if (!errors.isEmpty()) {
            List<Violation> violations = errors.stream()
                    .limit(MAX_REPORTED_VIOLATIONS)
                    .map(JsonSchemaMetricBatchParser::toViolation)
                    .toList();
            throw new InvalidRequestException("The request body violates the metric batch schema", violations);
        }
        JsonNode pointsNode = root.get("points");
        List<MetricBatch.Point> points = new ArrayList<>(pointsNode.size());
        for (int index = 0; index < pointsNode.size(); index++) {
            JsonNode point = pointsNode.get(index);
            // A number beyond the double range cannot be represented; NaN marks it, the service rejects the point.
            points.add(new MetricBatch.Point(index, point.get("metricId").stringValue(),
                    point.get("ts").stringValue(), point.get("value").doubleValue(Double.NaN)));
        }
        JsonNode source = root.get("source");
        return new MetricBatch(points, source == null ? null : source.stringValue());
    }

    private static Violation toViolation(Error error) {
        String location = error.getInstanceLocation() == null ? "" : error.getInstanceLocation().toString();
        return new Violation(location, error.getMessage());
    }
}
