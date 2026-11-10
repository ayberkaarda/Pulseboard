package io.pulseboard.core.api.internal;

import io.pulseboard.core.api.anomaly.AnomalyDtos.NotificationRequest;
import io.pulseboard.core.api.anomaly.AnomalyDtos.NotificationResponse;
import io.pulseboard.core.api.anomaly.AnomalyMapper;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.anomaly.AnomalyNotificationParser;
import io.pulseboard.core.application.anomaly.AnomalyNotificationService;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service to service endpoint of the analytics service. The request body was already read, size-limited and
 * authenticated by the signature filter of the {@code /internal} filter chain; this controller only sees bodies
 * whose HMAC matched.
 */
@RestController
@Tag(name = "internal", description = "Service to service endpoints, not part of the public API. Authenticated "
        + "with an HMAC signature of the body, never with a JWT or an API key.")
public class InternalAnomalyController {

    private final AnomalyNotificationParser parser;
    private final AnomalyNotificationService notificationService;
    private final AnomalyMapper mapper;

    public InternalAnomalyController(AnomalyNotificationParser parser,
            AnomalyNotificationService notificationService, AnomalyMapper mapper) {
        this.parser = parser;
        this.notificationService = notificationService;
        this.mapper = mapper;
    }

    @PostMapping(path = "/internal/v1/anomalies/notify", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Announce newly stored anomalies",
            description = "Called by the analytics service after it inserted anomalies. The body follows "
                    + "contracts/events/anomaly-detected.schema.json (1 to 500 entries, at most 512 KiB). Only the "
                    + "id and tenantId of each entry are used: every id is read again from the database together "
                    + "with that tenant, and what the database holds is published to the live streams of the "
                    + "tenant. Ids that do not exist, or that belong to another tenant than claimed, are counted "
                    + "in `unknown` and nothing is published for them. Nothing is inserted, so a replay is "
                    + "harmless.",
            security = @SecurityRequirement(name = OpenApiConfiguration.INTERNAL_HMAC_AUTH),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NotificationRequest.class))),
            responses = {
                @ApiResponse(responseCode = "202", description = "Notification processed",
                        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = NotificationResponse.class))),
                @ApiResponse(responseCode = "400", description = "Not JSON or violates the notification schema",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "401", description = "X-Internal-Signature missing, malformed or wrong",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "413", description = "Body larger than the configured limit",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "500", description = "Unexpected error",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "503", description = "The message bus is unavailable; retry",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public ResponseEntity<NotificationResponse> notifyAnomalies(HttpServletRequest request) {
        byte[] body;
        try {
            body = request.getInputStream().readAllBytes();
        } catch (IOException ex) {
            throw new InvalidRequestException("The request body could not be read");
        }
        return ResponseEntity.accepted()
                .body(mapper.toResponse(notificationService.notify(parser.parse(body))));
    }
}
