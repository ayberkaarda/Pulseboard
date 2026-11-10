package io.pulseboard.core.api.auth;

import io.pulseboard.core.api.auth.AuthDtos.LoginRequest;
import io.pulseboard.core.api.auth.AuthDtos.LogoutRequest;
import io.pulseboard.core.api.auth.AuthDtos.RefreshRequest;
import io.pulseboard.core.api.auth.AuthDtos.RegisterRequest;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.openapi.OpenApiConfiguration;
import io.pulseboard.core.api.openapi.ProblemDocument;
import io.pulseboard.core.api.ratelimit.ClientRateLimited;
import io.pulseboard.core.application.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registration, login, refresh-token rotation and logout. Token responses are never cached. */
@RestController
@Tag(name = "Authentication")
@ClientRateLimited
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthMapper authMapper;

    public AuthController(AuthService authService, AuthMapper authMapper) {
        this.authService = authService;
        this.authMapper = authMapper;
    }

    @PostMapping("/register")
    @ApiResponse(responseCode = "201", description = "Created", useReturnTypeSchema = true)
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        TokenResponse body = authMapper.toResponse(authService.register(authMapper.toCommand(request)));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse body = authMapper.toResponse(authService.login(authMapper.toCommand(request)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/logout")
    @Operation(summary = "End the session",
            description = "Revokes the whole refresh-token family of the presented refresh token, so neither it "
                    + "nor any token rotated from it can be exchanged again. Needs no access token (it may "
                    + "already have expired; one that is sent is ignored). Idempotent: an unknown, expired or "
                    + "already revoked token is answered with 204 exactly like a valid one.",
            responses = {
                @ApiResponse(responseCode = "204", description = "Session ended (or there was none)"),
                @ApiResponse(responseCode = "400", description = "Malformed body",
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class))),
                @ApiResponse(responseCode = "429", description = "Too many requests from this client",
                        headers = @Header(name = "Retry-After", description = "Seconds until a retry can succeed",
                                schema = @Schema(type = "integer")),
                        content = @Content(mediaType = OpenApiConfiguration.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDocument.class)))
            })
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenResponse body = authMapper.toResponse(authService.refresh(request.refreshToken()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
