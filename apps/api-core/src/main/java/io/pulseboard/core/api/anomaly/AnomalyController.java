package io.pulseboard.core.api.anomaly;

import io.pulseboard.core.api.anomaly.AnomalyDtos.AnomalyPageResponse;
import io.pulseboard.core.api.anomaly.AnomalyDtos.AnomalyResponse;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.anomaly.AnomalyService;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.pulseboard.core.domain.anomaly.AnomalyPage;
import io.pulseboard.core.domain.anomaly.AnomalyQuery;
import io.pulseboard.core.domain.anomaly.Severity;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anomalies of the calling tenant. An anomaly of another tenant is answered with 404, exactly like an id that does
 * not exist. Reading is open to every user; acknowledging is for OWNER and ADMIN.
 */
@RestController
@Tag(name = "Anomalies")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
@RequestMapping(path = "/api/v1/anomalies", produces = MediaType.APPLICATION_JSON_VALUE)
public class AnomalyController {

    private final AnomalyService anomalyService;
    private final AnomalyMapper mapper;
    private final TenantContext tenantContext;

    public AnomalyController(AnomalyService anomalyService, AnomalyMapper mapper, TenantContext tenantContext) {
        this.anomalyService = anomalyService;
        this.mapper = mapper;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    @Operation(summary = "List anomalies",
            description = "Anomalies of the caller's tenant, newest window first (window start, then id, both "
                    + "descending), so consecutive pages never overlap. `from` (inclusive) and `to` (exclusive) "
                    + "filter on the window start. At most 100 per page.",
            responses = {
                @ApiResponse(responseCode = "200", description = "One page of anomalies"),
                @ApiResponse(responseCode = "400", description = "Invalid filter or paging parameter",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "401", description = "Missing or invalid access token",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "403", description = "Not authenticated as a user",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "500", description = "Unexpected error",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public AnomalyPageResponse list(
            @Parameter(description = "Only anomalies of this metric.")
            @RequestParam(name = "metricId", required = false) UUID metricId,
            @Parameter(description = "Only anomalies of metrics of this service.")
            @RequestParam(name = "serviceId", required = false) UUID serviceId,
            @Parameter(description = "Only anomalies of this severity.")
            @RequestParam(name = "severity", required = false) Severity severity,
            @Parameter(description = "true: only acknowledged anomalies, false: only open ones.")
            @RequestParam(name = "acknowledged", required = false) Boolean acknowledged,
            @Parameter(description = "Inclusive lower bound of the window start, RFC 3339 (encode '+' as %2B).")
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive upper bound of the window start, RFC 3339 (encode '+' as %2B).")
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Zero-based page number.",
                    schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, 1 to 100.",
                    schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1",
                            maximum = "100"))
            @RequestParam(name = "size", defaultValue = "" + AnomalyService.DEFAULT_PAGE_SIZE) int size) {
        AnomalyQuery query = new AnomalyQuery(metricId, serviceId, severity, acknowledged,
                from == null ? null : from.toInstant(), to == null ? null : to.toInstant());
        AnomalyPage result = anomalyService.list(tenantContext.requireTenantId(), query, page, size);
        long totalPages = (result.totalItems() + size - 1) / size;
        return new AnomalyPageResponse(result.items().stream().map(mapper::toResponse).toList(), page, size,
                result.totalItems(), totalPages);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one anomaly",
            responses = {
                @ApiResponse(responseCode = "200", description = "The anomaly"),
                @ApiResponse(responseCode = "401", description = "Missing or invalid access token",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "403", description = "Not authenticated as a user",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "404", description = "No such anomaly for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "500", description = "Unexpected error",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public AnomalyResponse get(@PathVariable("id") UUID id) {
        return mapper.toResponse(anomalyService.get(tenantContext.requireTenantId(), id));
    }

    @PostMapping("/{id}/ack")
    @Operation(summary = "Acknowledge an anomaly",
            description = "OWNER and ADMIN only. Idempotent: the first acknowledgement is kept, a later call "
                    + "returns the stored state with 200 and changes nothing.",
            responses = {
                @ApiResponse(responseCode = "200", description = "The acknowledged anomaly"),
                @ApiResponse(responseCode = "401", description = "Missing or invalid access token",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "403", description = "Not an OWNER or ADMIN user",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "404", description = "No such anomaly for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "409", description = "The acknowledging user no longer exists",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "500", description = "Unexpected error",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public AnomalyResponse acknowledge(@PathVariable("id") UUID id,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return mapper.toResponse(anomalyService.acknowledge(tenantContext.requireTenantId(), userId(jwt), id));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new InvalidRequestException("The access token does not identify a user");
        }
    }
}
