package io.pulseboard.core.api.auth;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response bodies of the authentication endpoints. */
public final class AuthDtos {

    /** Lower-case letters, digits and inner hyphens; 3 to 63 characters. */
    public static final String SLUG_PATTERN = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$";

    private AuthDtos() {
    }

    /** Creates a tenant and its OWNER user. */
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = SLUG_PATTERN, message = "must be 3-63 lower-case letters, digits or hyphens")
            String tenantSlug,
            @NotBlank @Size(max = 200) String tenantName,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {
    }

    /** Password login; the tenant slug disambiguates emails that exist in several tenants. */
    public record LoginRequest(
            @NotBlank @Size(max = 63) String tenantSlug,
            @NotBlank @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    /** Exchanges a refresh token for a new token pair. */
    public record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
    }

    /** Ends a session: revokes the whole refresh-token family of the presented token. */
    public record LogoutRequest(@NotBlank @Size(max = 256) String refreshToken) {
    }

    /** Token pair returned by register, login and refresh. */
    public record TokenResponse(
            @Schema(requiredMode = REQUIRED) String tokenType,
            @Schema(requiredMode = REQUIRED) String accessToken,
            @Schema(requiredMode = REQUIRED) Instant accessTokenExpiresAt,
            @Schema(requiredMode = REQUIRED) String refreshToken,
            @Schema(requiredMode = REQUIRED,
                    description = "End of the refresh token's validity; never later than the end of the session "
                            + "(absolute refresh lifetime).")
            Instant refreshTokenExpiresAt,
            @Schema(requiredMode = REQUIRED) UUID userId,
            @Schema(requiredMode = REQUIRED) UUID tenantId,
            @Schema(requiredMode = REQUIRED) String role) {
    }
}
