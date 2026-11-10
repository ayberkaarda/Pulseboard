package io.pulseboard.core.application.auth;

import io.pulseboard.core.domain.identity.Role;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;

/** Result of a successful register, login or refresh: a fresh access token and a fresh refresh token. */
public record AuthTokens(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UUID userId,
        TenantId tenantId,
        Role role) {
}
