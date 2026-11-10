package io.pulseboard.core.api.auth;

import io.pulseboard.core.api.auth.AuthDtos.LoginRequest;
import io.pulseboard.core.api.auth.AuthDtos.RefreshRequest;
import io.pulseboard.core.api.auth.AuthDtos.RegisterRequest;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.ratelimit.ClientRateLimited;
import io.pulseboard.core.application.auth.AuthService;
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

/** Registration, login and refresh-token rotation. Token responses are never cached. */
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

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenResponse body = authMapper.toResponse(authService.refresh(request.refreshToken()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
