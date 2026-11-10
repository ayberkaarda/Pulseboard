package io.pulseboard.core.api.stream;

import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live anomalies of the caller's tenant as server-sent events. */
@RestController
@Tag(name = "Anomalies")
public class AnomalyStreamController {

    private final AnomalyStreams streams;
    private final TenantContext tenantContext;

    public AnomalyStreamController(AnomalyStreams streams, TenantContext tenantContext) {
        this.streams = streams;
        this.tenantContext = tenantContext;
    }

    @GetMapping(path = "/api/v1/stream/anomalies", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream new anomalies",
            // Documented here, not as a method parameter: the ticket is read by the security filter, and a bound
            // controller argument would be echoed by the framework when it logs handler arguments.
            parameters = @io.swagger.v3.oas.annotations.Parameter(name = "ticket", in = ParameterIn.QUERY,
                    description = "One-time stream ticket from POST /api/v1/stream/ticket, as an alternative to "
                            + "the Authorization header. Consumed by the request. Never log or store it.",
                    schema = @Schema(type = "string", minLength = 43, maxLength = 43)),
            security = {
                @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH),
                @SecurityRequirement(name = OpenApiConfiguration.STREAM_TICKET_AUTH)
            },
            description = "Server-sent events (`text/event-stream`) for the caller's tenant only. Each anomaly is "
                    + "one event named `anomaly` whose `id` is the anomaly id and whose data is the Anomaly JSON "
                    + "document. A comment line `: ping` is sent every 30 seconds (configurable) while idle. "
                    + "Authenticate with exactly one of: the bearer access token, or a one-time `ticket` query "
                    + "parameter from POST /api/v1/stream/ticket (for a browser EventSource, which cannot set the "
                    + "header). A request with both, or with an invalid, expired, malformed or already used ticket, "
                    + "is answered with the same 401; a ticket is used up even when the request is then refused "
                    + "(for example with 429), so request a new one for every connection attempt. Access tokens in "
                    + "the query string are not accepted. The stream ends when the access token (the one that "
                    + "requested the ticket, for ticket streams) expires or after at most 30 minutes "
                    + "(configurable); reconnect with a fresh token or ticket. Events published while "
                    + "disconnected are not replayed: read GET /api/v1/anomalies after reconnecting. Limited per "
                    + "tenant and per instance (defaults 10 and 500).",
            responses = {
                @ApiResponse(responseCode = "200", description = "Event stream",
                        content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                schema = @Schema(type = "string"))),
                @ApiResponse(responseCode = "401", description = "Missing or invalid credentials, both credentials at once, or an invalid ticket",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "403", description = "Not authenticated as a user",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "429", description = "Too many open streams for the tenant or the server",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "500", description = "Unexpected error",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "503", description = "The message bus is unavailable",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public SseEmitter stream(@io.swagger.v3.oas.annotations.Parameter(hidden = true)
            @AuthenticationPrincipal Jwt jwt) {
        return streams.open(tenantContext.requireTenantId(), jwt.getExpiresAt());
    }
}
