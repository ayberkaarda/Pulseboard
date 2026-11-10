package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.identity.User;
import io.pulseboard.core.domain.identity.UserRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

/** Spring Data implementation of {@link UserRepository}; queries are derived from the port's method names. */
interface JpaUserRepository extends UserRepository, Repository<User, UUID> {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findLockedByIdAndTenantId(UUID id, TenantId tenantId);
}
