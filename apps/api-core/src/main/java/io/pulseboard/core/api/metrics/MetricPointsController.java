package io.pulseboard.core.api.metrics;

import io.pulseboard.core.api.metrics.MetricPointsDtos.MetricPointsResponse;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.InvalidRequestException.Violation;
import io.pulseboard.core.application.query.MetricQueryService;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Downsampled metric series for dashboard users (JWT). A metric of another tenant is answered with 404. */
@RestController
@Tag(name = "Metrics")
public class MetricPointsController {

    private final MetricQueryService queryService;
    private final MetricPointsMapper mapper;
    private final TenantContext tenantContext;

    public MetricPointsController(MetricQueryService queryService, MetricPointsMapper mapper,
            TenantContext tenantContext) {
        this.queryService = queryService;
        this.mapper = mapper;
        this.tenantContext = tenantContext;
    }

    @GetMapping(path = "/api/v1/metrics/{metricId}/points", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read a metric downsampled on the server",
            description = "Buckets of `step` aligned to the Unix epoch, combined with the metric's aggregation "
                    + "(AVG, SUM, MAX or P95 as a continuous percentile). Covers `from <= ts < to`; buckets "
                    + "without samples are omitted. At most 2000 buckets: without `step` the smallest step of "
                    + "1s, 5s, 10s, 15s, 30s, 1m, 2m, 5m, 10m, 15m, 30m, 1h, 2h, 3h, 6h, 12h, 1d, 2d or 7d that "
                    + "fits is used; an explicit step that would yield more buckets is rejected with 400. "
                    + "Limits: the range covers at most 366 days, from/to lie between 1970-01-01T00:00:00Z and "
                    + "9999-12-31T23:59:59Z, step is whole seconds between PT1S and P366D. from/to are rounded up "
                    + "to whole microseconds (the storage precision) and echoed normalised. Rate limited per "
                    + "tenant (default 120 queries per minute); each query runs under a statement timeout "
                    + "(default 5 s).",
            security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH),
            responses = {
                @ApiResponse(responseCode = "200", description = "Downsampled series",
                        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = MetricPointsResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid range or step",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "401", description = "Missing or invalid access token",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "403", description = "Not authenticated as a user",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "404", description = "No such metric for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "422",
                        description = "An aggregate of the stored samples exceeds the double range (type "
                                + "aggregate-out-of-range; not reachable with values accepted by ingestion)",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "429", description = "Tenant query rate limit exceeded",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "500", description = "Unexpected error",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "503",
                        description = "Query exceeded the statement timeout (type query-timeout) or the rate "
                                + "limiter is unavailable",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public MetricPointsResponse points(
            @PathVariable("metricId") UUID metricId,
            @Parameter(description = "Inclusive start, RFC 3339 date-time (encode '+' as %2B).", required = true)
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive end, RFC 3339 date-time (encode '+' as %2B).", required = true)
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Bucket width as an ISO 8601 duration of whole seconds, at least PT1S.",
                    schema = @Schema(type = "string", example = "PT5M"))
            @RequestParam(name = "step", required = false) String step) {
        return mapper.toResponse(queryService.points(tenantContext.requireTenantId(), metricId, from.toInstant(),
                to.toInstant(), parseStep(step)));
    }

    private static Duration parseStep(String step) {
        if (step == null || step.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(step.trim());
        } catch (DateTimeParseException ex) {
            throw new InvalidRequestException("The query parameters are invalid",
                    List.of(new Violation("step", "must be an ISO 8601 duration such as PT5M")));
        }
    }
}
