package io.pulseboard.core.infrastructure.anomaly;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import io.pulseboard.core.application.anomaly.AnomalyNotificationParser;
import io.pulseboard.core.application.anomaly.NotifiedAnomaly;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.InvalidRequestException.Violation;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AnomalyNotificationParser} that validates against the bundled copy of
 * {@code contracts/events/anomaly-detected.schema.json} (JSON Schema 2020-12). Formats are asserted, duplicate keys
 * and trailing content are rejected, so the validated document is exactly the one that is read.
 */
@Component
public class JsonSchemaAnomalyNotificationParser implements AnomalyNotificationParser {

    /** Classpath location of the bundled schema; a test keeps it identical to the contract file. */
    public static final String SCHEMA_RESOURCE = "schemas/anomaly-detected.schema.json";

    static final int MAX_REPORTED_VIOLATIONS = 20;

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final Schema schema;

    public JsonSchemaAnomalyNotificationParser() {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
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
    public List<NotifiedAnomaly> parse(byte[] json) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(json);
        } catch (JacksonException ex) {
            throw new InvalidRequestException("The request body is not valid JSON",
                    List.of(new Violation("", "malformed JSON")));
        }
        if (root == null || root.isMissingNode()) {
            throw new InvalidRequestException("The request body is empty");
        }
        List<Error> errors = schema.validate(root);
        if (!errors.isEmpty()) {
            List<Violation> violations = errors.stream()
                    .limit(MAX_REPORTED_VIOLATIONS)
                    .map(error -> new Violation(
                            error.getInstanceLocation() == null ? "" : error.getInstanceLocation().toString(),
                            error.getMessage()))
                    .toList();
            throw new InvalidRequestException("The request body violates the anomaly notification schema",
                    violations);
        }
        JsonNode anomalies = root.get("anomalies");
        List<NotifiedAnomaly> notified = new ArrayList<>(anomalies.size());
        for (int index = 0; index < anomalies.size(); index++) {
            JsonNode entry = anomalies.get(index);
            notified.add(new NotifiedAnomaly(UUID.fromString(entry.get("id").stringValue()),
                    TenantId.parse(entry.get("tenantId").stringValue())));
        }
        return notified;
    }
}
