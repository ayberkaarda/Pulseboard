package io.pulseboard.core.domain.identity;

import io.pulseboard.core.domain.shared.CredentialLookup;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link ApiKey}. State changes are single-column conditional updates, so a usage write can
 * never overwrite a concurrent revocation (no read-modify-write of the whole row).
 */
public interface ApiKeyRepository {

    ApiKey save(ApiKey apiKey);

    Optional<ApiKey> findByIdAndTenantId(UUID id, TenantId tenantId);

    /** Resolves a presented key by its SHA-256 hash; this is how the tenant of a machine caller is found. */
    @CredentialLookup
    Optional<ApiKey> findByKeyHash(String keyHash);

    /**
     * Sets {@code last_used_at} to {@code usedAt} when the key is still active and was last used before
     * {@code staleBefore} (or never). Touches no other column.
     *
     * @return number of rows updated (0 or 1)
     */
    int recordUsage(UUID id, TenantId tenantId, Instant usedAt, Instant staleBefore);

    /**
     * Sets {@code revoked_at} when the key is not revoked yet; an earlier revocation time is kept.
     *
     * @return number of rows updated (0 or 1)
     */
    int revoke(UUID id, TenantId tenantId, Instant revokedAt);
}
