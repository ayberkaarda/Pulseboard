package io.pulseboard.core.domain.identity;

import io.pulseboard.core.domain.shared.CredentialLookup;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link RefreshToken}. */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken refreshToken);

    /**
     * Resolves the owner of a presented refresh token by its SHA-256 hash without taking any lock, so the caller
     * can serialize on the owner before touching token rows.
     */
    @CredentialLookup
    Optional<RefreshTokenOwner> findOwnerByTokenHash(String tokenHash);

    /**
     * Resolves a presented refresh token by its SHA-256 hash and locks the row for the rest of the transaction.
     */
    @CredentialLookup
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every still-active token of a family (reuse detection, logout, absolute expiry).
     *
     * @return number of tokens revoked
     */
    int revokeFamily(UUID familyId, TenantId tenantId, Instant revokedAt);
}
