package io.pulseboard.core.api.alert;

import io.pulseboard.core.api.alert.AlertDtos.AlertEventPageResponse;
import io.pulseboard.core.api.alert.AlertDtos.AlertEventResponse;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.alert.AlertEventService;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.pulseboard.core.domain.alert.AlertEventPage;
import io.pulseboard.core.domain.alert.AlertEventQuery;
import io.pulseboard.core.domain.alert.DeliveryStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alert history of the calling tenant, read-only and open to every user. API keys are refused. */
@RestController
@Tag(name = "Alert events")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
@RequestMapping(path = "/api/v1/alert-events", produces = MediaType.APPLICATION_JSON_VALUE)
@ApiResponse(responseCode = "401", description = "Missing or invalid access token",
        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDocument.class)))
@ApiResponse(responseCode = "403", description = "Not authenticated as a user",
        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDocument.class)))
@ApiResponse(responseCode = "500", description = "Unexpected error",
        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDocument.class)))
public class AlertEventController {

    private final AlertEventService eventService;
    private final TenantContext tenantContext;

    public AlertEventController(AlertEventService eventService, TenantContext tenantContext) {
        this.eventService = eventService;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    @Operation(operationId = "listAlertEvents", summary = "List alert events",
            description = "Alert events of the caller's tenant, newest first (trigger time, then id, both "
                    + "descending), so consecutive pages never overlap. `from` (inclusive) and `to` (exclusive) "
                    + "filter on the trigger time. At most 100 per page.",
            responses = {
                @ApiResponse(responseCode = "200", description = "One page of alert events"),
                @ApiResponse(responseCode = "400", description = "Invalid filter or paging parameter",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public AlertEventPageResponse list(
            @Parameter(description = "Only events of this rule.")
            @RequestParam(name = "ruleId", required = false) UUID ruleId,
            @Parameter(description = "Only events with this delivery status.")
            @RequestParam(name = "status", required = false) DeliveryStatus status,
            @Parameter(description = "Inclusive lower bound of the trigger time, RFC 3339 (encode '+' as %2B).")
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive upper bound of the trigger time, RFC 3339 (encode '+' as %2B).")
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Zero-based page number.",
                    schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, 1 to 100.",
                    schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1",
                            maximum = "100"))
            @RequestParam(name = "size", defaultValue = "" + AlertEventService.DEFAULT_PAGE_SIZE) int size) {
        AlertEventQuery query = new AlertEventQuery(ruleId, status, from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant());
        AlertEventPage result = eventService.list(tenantContext.requireTenantId(), query, page, size);
        long totalPages = (result.totalItems() + size - 1) / size;
        return new AlertEventPageResponse(result.items().stream().map(AlertMapper::toResponse).toList(), page, size,
                result.totalItems(), totalPages);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getAlertEvent", summary = "Read one alert event",
            responses = {
                @ApiResponse(responseCode = "200", description = "The event"),
                @ApiResponse(responseCode = "404", description = "No such event for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public AlertEventResponse get(@PathVariable("id") UUID id) {
        return AlertMapper.toResponse(eventService.get(tenantContext.requireTenantId(), id));
    }
}
