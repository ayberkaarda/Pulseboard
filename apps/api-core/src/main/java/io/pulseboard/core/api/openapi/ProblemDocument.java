package io.pulseboard.core.api.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * OpenAPI description of the RFC 9457 problem documents the API returns. Spring's {@code ProblemDetail} keeps
 * extension members in a {@code properties} map that is serialised at the top level, so documenting that class
 * directly would show a {@code properties} member that never appears on the wire. Only used for documentation.
 */
@Schema(name = "Problem", description = "RFC 9457 problem details (application/problem+json).")
public record ProblemDocument(
        @Schema(description = "Problem type URI, urn:pulseboard:problem:<kind>.",
                example = "urn:pulseboard:problem:validation")
        String type,
        String title,
        int status,
        String detail,
        @Schema(description = "Request path.") String instance,
        @Schema(description = "Present on validation problems: one entry per violation.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<FieldError> errors) {

    /**
     * One violation.
     *
     * @param field a JSON pointer into the body (for example {@code /points/1/ts}) or a parameter name
     * @param message what is wrong
     */
    @Schema(name = "ProblemFieldError")
    public record FieldError(String field, String message) {
    }
}
