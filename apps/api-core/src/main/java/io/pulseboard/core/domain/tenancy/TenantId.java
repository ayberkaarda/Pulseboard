package io.pulseboard.core.domain.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed tenant identifier. Using a dedicated type (rather than a bare {@link UUID}) lets the
 * architecture tests verify that tenant-scoped repository methods are always scoped.
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId of(UUID value) {
        return new TenantId(value);
    }

    public static TenantId parse(String value) {
        return new TenantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
