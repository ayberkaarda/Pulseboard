package io.pulseboard.core.api.ingest;

import io.pulseboard.core.api.ingest.IngestDtos.IngestResponse;
import io.pulseboard.core.api.ingest.IngestDtos.MetricBatchRequest;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.ingest.IngestionResult;
import io.pulseboard.core.application.ingest.IngestionService;
import io.pulseboard.core.application.ingest.PayloadTooLargeException;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metric ingestion for machine callers ({@code X-Api-Key}).
 *
 * <p>The body is read as raw bytes with a hard size limit and validated against the metric batch JSON Schema, not
 * bound to a Java type: the limit holds for chunked bodies too, and the schema is the single definition of a
 * valid batch.
 */
@RestController
@Tag(name = "Ingestion")
public class IngestController {

    private final IngestionService ingestionService;
    private final IngestMapper ingestMapper;
    private final TenantContext tenantContext;

    public IngestController(IngestionService ingestionService, IngestMapper ingestMapper,
            TenantContext tenantContext) {
        this.ingestionService = ingestionService;
        this.ingestMapper = ingestMapper;
        this.tenantContext = tenantContext;
    }

    @PostMapping(path = "/api/v1/ingest", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Ingest a batch of metric points",
            description = "Stores up to 1000 points. Points that cannot be stored (unknown metric, timestamp "
                    + "outside the storable range, value not finite or |value| > 1e100, duplicate of a later "
                    + "point with the same metricId and ts) are listed in `rejected`; the others are stored. An "
                    + "existing sample with the same metric and timestamp is overwritten. Rate limited per tenant "
                    + "(defaults: FREE 60, PRO 600 batches per minute) and, before the key is looked up, per "
                    + "client address (all API key requests, and failed API key authentications separately).",
            security = @SecurityRequirement(name = OpenApiConfiguration.API_KEY_AUTH),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MetricBatchRequest.class))),
            responses = {
                @ApiResponse(responseCode = "202", description = "Batch processed",
                        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = IngestResponse.class))),
                @ApiResponse(responseCode = "400", description = "Not JSON or violates the batch schema",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "401", description = "Missing, invalid or revoked API key",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "403", description = "Not authenticated with an API key",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "413", description = "Body larger than the configured limit",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "415", description = "Content type is not application/json",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "409",
                        description = "Storage changed while the batch was written (for example a partition was "
                                + "removed); nothing was stored",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "429", description = "Tenant or client rate limit exceeded",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "500", description = "Unexpected error",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "503",
                        description = "Rate limiter unavailable (fails closed) or the batch lost a lock conflict "
                                + "with a concurrent write (type concurrency-conflict; nothing was stored, retry)",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public ResponseEntity<IngestResponse> ingest(HttpServletRequest request) {
        int maxBytes = ingestionService.maxBodyBytes();
        IngestionResult result = ingestionService.ingest(tenantContext.requireTenantId(),
                () -> readBody(request, maxBytes));
        return ResponseEntity.accepted().body(ingestMapper.toResponse(result));
    }

    /** Reads at most {@code maxBytes}; a larger declared or actual body is rejected without reading further. */
    private static byte[] readBody(HttpServletRequest request, int maxBytes) {
        if (request.getContentLengthLong() > maxBytes) {
            throw new PayloadTooLargeException(maxBytes);
        }
        try {
            InputStream body = request.getInputStream();
            byte[] bytes = body.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            return bytes;
        } catch (IOException ex) {
            throw new InvalidRequestException("The request body could not be read");
        }
    }
}
