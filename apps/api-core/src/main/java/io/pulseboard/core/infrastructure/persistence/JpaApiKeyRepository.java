package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.identity.ApiKey;
import io.pulseboard.core.domain.identity.ApiKeyRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link ApiKeyRepository}. */
interface JpaApiKeyRepository extends ApiKeyRepository, Repository<ApiKey, UUID> {

    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ApiKey k
               set k.lastUsedAt = :usedAt
             where k.id = :id
               and k.tenantId = :tenantId
               and k.revokedAt is null
               and (k.lastUsedAt is null or k.lastUsedAt < :staleBefore)
            """)
    int recordUsage(@Param("id") UUID id, @Param("tenantId") TenantId tenantId, @Param("usedAt") Instant usedAt,
            @Param("staleBefore") Instant staleBefore);

    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ApiKey k
               set k.revokedAt = :revokedAt
             where k.id = :id
               and k.tenantId = :tenantId
               and k.revokedAt is null
            """)
    int revoke(@Param("id") UUID id, @Param("tenantId") TenantId tenantId, @Param("revokedAt") Instant revokedAt);
}
