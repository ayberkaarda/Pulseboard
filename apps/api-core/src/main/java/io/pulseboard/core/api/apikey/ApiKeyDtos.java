package io.pulseboard.core.api.apikey;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response bodies of the API key endpoints. */
public final class ApiKeyDtos {

    private ApiKeyDtos() {
    }

    /** Issues a new key; the name is a human-readable label. */
    public record CreateApiKeyRequest(@NotBlank @Size(max = 100) String name) {
    }

    /** A newly issued key. {@code key} is returned only in this response and cannot be retrieved again. */
    public record ApiKeyCreatedResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String prefix,
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) Instant createdAt) {
    }
}
