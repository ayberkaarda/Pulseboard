package io.pulseboard.core.api.alert;

import io.pulseboard.core.api.alert.AlertDtos.AlertRuleEnabledRequest;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleRequest;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleUpdateRequest;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.application.alert.AlertRuleService;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.InvalidRequestException.Violation;
import io.pulseboard.core.application.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alert rules of the calling tenant. A rule of another tenant is answered with 404, exactly like an id that does not
 * exist. Reading is open to every user; creating, changing and deleting is for OWNER and ADMIN. API keys are refused.
 */
@RestController
@Tag(name = "Alert rules")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
@RequestMapping(path = "/api/v1/alert-rules", produces = MediaType.APPLICATION_JSON_VALUE)
@ApiResponse(responseCode = "401", description = "Missing or invalid access token",
        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDocument.class)))
@ApiResponse(responseCode = "403", description = "Not a user of the tenant, or not an OWNER or ADMIN for a change",
        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDocument.class)))
@ApiResponse(responseCode = "500", description = "Unexpected error",
        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDocument.class)))
public class AlertRuleController {

    private final AlertRuleService ruleService;
    private final TenantContext tenantContext;

    public AlertRuleController(AlertRuleService ruleService, TenantContext tenantContext) {
        this.ruleService = ruleService;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    @Operation(operationId = "listAlertRules", summary = "List alert rules",
            description = "All rules of the caller's tenant, oldest first (a tenant owns at most 50 by default). "
                    + "VIEWER users see the target of WEBHOOK rules masked to the host.",
            responses = @ApiResponse(responseCode = "200", description = "The rules"))
    public List<AlertRuleResponse> list(@Parameter(hidden = true) Authentication authentication) {
        boolean full = seesFullTarget(authentication);
        return ruleService.list(tenantContext.requireTenantId()).stream()
                .map(rule -> AlertMapper.toResponse(rule, full)).toList();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getAlertRule", summary = "Read one alert rule",
            responses = {
                @ApiResponse(responseCode = "200", description = "The rule"),
                @ApiResponse(responseCode = "404", description = "No such rule for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public AlertRuleResponse get(@PathVariable("id") UUID id,
            @Parameter(hidden = true) Authentication authentication) {
        return AlertMapper.toResponse(ruleService.get(tenantContext.requireTenantId(), id),
                seesFullTarget(authentication));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createAlertRule", summary = "Create an alert rule",
            description = "OWNER and ADMIN only. A WEBHOOK target is checked against server-side request forgery "
                    + "rules before it is stored (see the `target` field) and again for every delivery. "
                    + "Deliveries are POST requests with the event's `payload` as the JSON body and the headers "
                    + "`X-Pulseboard-Event-Id`, `X-Pulseboard-Timestamp` (Unix seconds) and "
                    + "`X-Pulseboard-Signature`: the lower-case hex HMAC-SHA256 of `<timestamp>.<raw body>`, keyed "
                    + "with the server's ALERT_WEBHOOK_SIGNING_SECRET. A receiver recomputes it over the bytes it "
                    + "received, compares in constant time, rejects old timestamps and deduplicates on the event id "
                    + "(delivery is at least once). Redirects are not followed: a 3xx answer is a permanent failure. "
                    + "2xx counts as delivered, 4xx other than 408 and 429 as a permanent failure; a transport "
                    + "error, 408, 429 and 5xx are retried (4 attempts by default, 10 s, 60 s and 300 s apart).",
            responses = {
                @ApiResponse(responseCode = "201", description = "The rule was created"),
                @ApiResponse(responseCode = "400", description = "Invalid field, unknown metric or unacceptable "
                        + "webhook target",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "409", description = "The name is taken (type conflict) or the tenant "
                        + "has the maximum number of rules (type alert-rule-limit)",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public ResponseEntity<AlertRuleResponse> create(@RequestBody AlertRuleRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        AlertRuleResponse created = AlertMapper.toResponse(ruleService.create(tenantContext.requireTenantId(),
                userId(jwt), AlertMapper.toCommand(request)), true);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateAlertRule", summary = "Replace an alert rule",
            description = "OWNER and ADMIN only. Optimistic concurrency: `version` must be the version that was "
                    + "read; if the rule changed meanwhile the answer is 409 (type version-conflict).",
            responses = {
                @ApiResponse(responseCode = "200", description = "The updated rule"),
                @ApiResponse(responseCode = "400", description = "Invalid field, unknown metric or unacceptable "
                        + "webhook target",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "404", description = "No such rule for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "409", description = "Stale version (type version-conflict) or the "
                        + "name is taken (type conflict)",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public AlertRuleResponse update(@PathVariable("id") UUID id, @RequestBody AlertRuleUpdateRequest request) {
        if (request.version() == null || request.version() < 0) {
            throw new InvalidRequestException("The alert rule is invalid",
                    List.of(new Violation("/version", "is required")));
        }
        return AlertMapper.toResponse(ruleService.update(tenantContext.requireTenantId(), id, request.version(),
                AlertMapper.toCommand(request)), true);
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setAlertRuleEnabled", summary = "Enable or disable an alert rule",
            description = "OWNER and ADMIN only. Without `version` the flag is simply set.",
            responses = {
                @ApiResponse(responseCode = "200", description = "The updated rule"),
                @ApiResponse(responseCode = "400", description = "`enabled` is missing",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "404", description = "No such rule for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "409", description = "Stale version (type version-conflict)",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public AlertRuleResponse setEnabled(@PathVariable("id") UUID id,
            @RequestBody AlertRuleEnabledRequest request) {
        if (request.enabled() == null) {
            throw new InvalidRequestException("The request is invalid",
                    List.of(new Violation("/enabled", "is required")));
        }
        return AlertMapper.toResponse(ruleService.setEnabled(tenantContext.requireTenantId(), id,
                request.enabled(), request.version()), true);
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteAlertRule", summary = "Delete an alert rule",
            description = "OWNER and ADMIN only. The events of the rule are deleted with it.",
            responses = {
                @ApiResponse(responseCode = "204", description = "The rule was deleted"),
                @ApiResponse(responseCode = "404", description = "No such rule for the caller's tenant",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        ruleService.delete(tenantContext.requireTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Only OWNER and ADMIN may see (and need) the full target; everybody else gets the masked form. */
    private static boolean seesFullTarget(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new InvalidRequestException("The access token does not identify a user");
        }
    }
}
