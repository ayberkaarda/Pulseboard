package io.pulseboard.core.application.streamticket;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a stream ticket stands for: the tenant and user it was issued to and the expiry of the access token that
 * requested it. A stream opened with the ticket never outlives that expiry.
 */
public record StreamTicketClaims(TenantId tenantId, UUID userId, Instant jwtExpiresAt) {

    public StreamTicketClaims {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(jwtExpiresAt, "jwtExpiresAt");
    }
}
