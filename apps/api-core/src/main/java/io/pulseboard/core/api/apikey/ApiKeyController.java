package io.pulseboard.core.api.apikey;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.apikey.ApiKeyDtos.CreateApiKeyRequest;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.application.apikey.ApiKeyService;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issue and revoke tenant API keys (OWNER and ADMIN only). */
@RestController
@Tag(name = "API keys")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ApiKeyMapper apiKeyMapper;
    private final TenantContext tenantContext;

    public ApiKeyController(ApiKeyService apiKeyService, ApiKeyMapper apiKeyMapper, TenantContext tenantContext) {
        this.apiKeyService = apiKeyService;
        this.apiKeyMapper = apiKeyMapper;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Created", useReturnTypeSchema = true)
    public ResponseEntity<ApiKeyCreatedResponse> issue(@Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyCreatedResponse body = apiKeyMapper.toCreatedResponse(
                apiKeyService.issue(tenantContext.requireTenantId(), request.name()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204", description = "Revoked")
    public ResponseEntity<Void> revoke(@PathVariable("id") UUID id) {
        apiKeyService.revoke(tenantContext.requireTenantId(), id);
        return ResponseEntity.noContent().build();
    }
}
