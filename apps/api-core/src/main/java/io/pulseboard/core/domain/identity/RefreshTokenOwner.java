package io.pulseboard.core.domain.identity;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.UUID;

/** Owner of a refresh token, read without locking the token row. */
public record RefreshTokenOwner(UUID userId, TenantId tenantId) {
}
