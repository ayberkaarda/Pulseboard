package io.pulseboard.core.api.catalog;

import io.pulseboard.core.api.catalog.CatalogDtos.MetricDefinitionResponse;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.catalog.ServiceCatalogService;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A single metric definition of the calling tenant. */
@RestController
@Tag(name = "Metrics")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
@RequestMapping("/api/v1/metrics")
public class MetricController {

    private final ServiceCatalogService catalogService;
    private final CatalogMapper catalogMapper;
    private final TenantContext tenantContext;

    public MetricController(ServiceCatalogService catalogService, CatalogMapper catalogMapper,
            TenantContext tenantContext) {
        this.catalogService = catalogService;
        this.catalogMapper = catalogMapper;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/{metricId}")
    @Operation(summary = "Get a metric definition",
            description = "The same representation as the items of a service's metric list (it carries the "
                    + "serviceId). 404 when the id is unknown or belongs to another tenant (identical responses).",
            responses = {
                @ApiResponse(responseCode = "200", description = "OK"),
                @ApiResponse(responseCode = "404", description = "No such metric for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public MetricDefinitionResponse getMetric(@PathVariable("metricId") UUID metricId) {
        return catalogMapper.toResponse(catalogService.getMetric(tenantContext.requireTenantId(), metricId));
    }
}
