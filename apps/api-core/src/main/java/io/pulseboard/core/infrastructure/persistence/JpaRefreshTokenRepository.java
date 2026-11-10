package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.identity.RefreshToken;
import io.pulseboard.core.domain.identity.RefreshTokenOwner;
import io.pulseboard.core.domain.identity.RefreshTokenRepository;
import io.pulseboard.core.domain.shared.CredentialLookup;
import io.pulseboard.core.domain.tenancy.TenantId;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link RefreshTokenRepository}. */
interface JpaRefreshTokenRepository extends RefreshTokenRepository, Repository<RefreshToken, UUID> {

    @Override
    @CredentialLookup
    @Query("""
            select new io.pulseboard.core.domain.identity.RefreshTokenOwner(t.userId, t.tenantId)
              from RefreshToken t
             where t.tokenHash = :tokenHash
            """)
    Optional<RefreshTokenOwner> findOwnerByTokenHash(@Param("tokenHash") String tokenHash);

    @Override
    @CredentialLookup
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :revokedAt
             where t.familyId = :familyId
               and t.tenantId = :tenantId
               and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("tenantId") TenantId tenantId,
            @Param("revokedAt") Instant revokedAt);
}
