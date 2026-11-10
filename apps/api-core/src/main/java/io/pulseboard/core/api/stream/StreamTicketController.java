package io.pulseboard.core.api.stream;

import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.streamticket.IssuedStreamTicket;
import io.pulseboard.core.application.streamticket.StreamTicketService;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issues the one-time tickets a browser {@code EventSource} uses to open the live stream. */
@RestController
@Tag(name = "Anomalies")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
public class StreamTicketController {

    private final StreamTicketService tickets;
    private final TenantContext tenantContext;

    public StreamTicketController(StreamTicketService tickets, TenantContext tenantContext) {
        this.tickets = tickets;
        this.tenantContext = tenantContext;
    }

    /** A ticket for the live stream. */
    public record StreamTicketResponse(
            @Schema(description = "Opaque one-time value for the `ticket` query parameter of the stream endpoint. "
                    + "256 random bits, URL-safe base64 without padding. Shown once; never log or store it.")
            String ticket,
            @Schema(description = "Seconds the ticket can still be used", example = "30")
            long expiresInSeconds) {

        /** Frameworks log response objects at TRACE; the ticket must not be part of that. */
        @Override
        public String toString() {
            return "StreamTicketResponse[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    @PostMapping(path = "/api/v1/stream/ticket")
    @Operation(operationId = "issueStreamTicket", summary = "Issue a stream ticket",
            description = "Returns a one-time ticket for GET /api/v1/stream/anomalies?ticket=..., for clients that "
                    + "cannot send an Authorization header (a browser EventSource). The ticket is valid for 30 "
                    + "seconds (configurable) or until the access token expires, whichever is sooner, is bound to "
                    + "the caller's tenant and user, and can be used exactly once. The stream opened with it ends "
                    + "when the access token that requested the ticket expires. Only the SHA-256 digest of the "
                    + "ticket is stored. Limited to 30 tickets per user per minute. Requires a user access token; "
                    + "API keys are refused. The response is never cached.",
            responses = {
                @ApiResponse(responseCode = "200", description = "Ticket issued"),
                @ApiResponse(responseCode = "401", description = "Missing or invalid access token",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "403", description = "Not authenticated as a user (for example an API key)",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "429", description = "Too many tickets requested",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "500", description = "Unexpected error",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "503", description = "The ticket store or rate limiter is unavailable "
                        + "(fails closed)",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public ResponseEntity<StreamTicketResponse> issue(@io.swagger.v3.oas.annotations.Parameter(hidden = true)
            @AuthenticationPrincipal Jwt jwt) {
        IssuedStreamTicket issued = tickets.issue(tenantContext.requireTenantId(), UUID.fromString(jwt.getSubject()),
                jwt.getExpiresAt());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(new StreamTicketResponse(issued.value(), issued.expiresInSeconds()));
    }
}
