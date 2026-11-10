package io.pulseboard.core.domain.identity;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link User}; every query is scoped to a tenant. */
public interface UserRepository {

    User save(User user);

    Optional<User> findByIdAndTenantId(UUID id, TenantId tenantId);

    /**
     * Loads the user and locks its row until the end of the transaction. Used to serialize all refresh-token
     * operations of one user, so a family revocation and a concurrent rotation can never interleave.
     */
    Optional<User> findLockedByIdAndTenantId(UUID id, TenantId tenantId);

    Optional<User> findByTenantIdAndEmail(TenantId tenantId, String email);
}
