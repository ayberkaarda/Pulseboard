package io.pulseboard.core.api.catalog;

import io.pulseboard.core.api.catalog.CatalogDtos.CreateMetricRequest;
import io.pulseboard.core.api.catalog.CatalogDtos.CreateServiceRequest;
import io.pulseboard.core.api.catalog.CatalogDtos.MetricDefinitionResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
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
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Services of the calling tenant and their metric definitions. A service id of another tenant is answered with
 * 404, exactly like an id that does not exist.
 */
@RestController
@Tag(name = "Services")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
@SecurityRequirement(name = OpenApiConfiguration.API_KEY_AUTH)
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceCatalogService catalogService;
    private final CatalogMapper catalogMapper;
    private final TenantContext tenantContext;

    public ServiceController(ServiceCatalogService catalogService, CatalogMapper catalogMapper,
            TenantContext tenantContext) {
        this.catalogService = catalogService;
        this.catalogMapper = catalogMapper;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public List<ServiceResponse> listServices() {
        return catalogMapper.toServiceResponses(catalogService.listServices(tenantContext.requireTenantId()));
    }

    @GetMapping("/{serviceId}")
    @Operation(summary = "Get a service",
            description = "404 when the id is unknown or belongs to another tenant (identical responses).",
            responses = {
                @ApiResponse(responseCode = "200", description = "OK"),
                @ApiResponse(responseCode = "404", description = "No such service for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public ServiceResponse getService(@PathVariable("serviceId") UUID serviceId) {
        return catalogMapper.toResponse(catalogService.getService(tenantContext.requireTenantId(), serviceId));
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Created", useReturnTypeSchema = true)
    public ResponseEntity<ServiceResponse> createService(@Valid @RequestBody CreateServiceRequest request) {
        ServiceResponse body = catalogMapper.toResponse(
                catalogService.createService(tenantContext.requireTenantId(), request.name(), request.environment()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{serviceId}/metrics")
    public List<MetricDefinitionResponse> listMetrics(@PathVariable("serviceId") UUID serviceId) {
        return catalogMapper.toMetricResponses(
                catalogService.listMetrics(tenantContext.requireTenantId(), serviceId));
    }

    @PostMapping("/{serviceId}/metrics")
    @ApiResponse(responseCode = "201", description = "Created", useReturnTypeSchema = true)
    public ResponseEntity<MetricDefinitionResponse> createMetric(@PathVariable("serviceId") UUID serviceId,
            @Valid @RequestBody CreateMetricRequest request) {
        MetricDefinitionResponse body = catalogMapper.toResponse(catalogService.createMetric(
                tenantContext.requireTenantId(), serviceId, request.name(), request.unit(), request.aggregation()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
