package io.pulseboard.core.api.catalog;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.pulseboard.core.domain.catalog.Aggregation;
import io.pulseboard.core.domain.catalog.Environment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response bodies of the service catalogue endpoints. */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CreateServiceRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull Environment environment) {
    }

    public record ServiceResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) Environment environment,
            @Schema(requiredMode = REQUIRED) Instant createdAt) {
    }

    public record CreateMetricRequest(
            @NotBlank @Size(max = 100)
            @Pattern(regexp = "^[a-z][a-z0-9_.]*$",
                    message = "must start with a lower-case letter and contain only a-z, 0-9, '_' or '.'")
            String name,
            @NotBlank @Size(max = 32) String unit,
            @NotNull Aggregation aggregation) {
    }

    public record MetricDefinitionResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID serviceId,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String unit,
            @Schema(requiredMode = REQUIRED) Aggregation aggregation,
            @Schema(requiredMode = REQUIRED) Instant createdAt) {
    }
}
