package io.pulseboard.core.domain.identity;

import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Machine credential of a tenant. Only the SHA-256 hash of the key is stored; the first eight characters of
 * the random part are kept as a visible prefix so users can tell keys apart. The mutable columns
 * ({@code last_used_at}, {@code revoked_at}) are changed only through targeted repository updates, never by
 * flushing this entity, so concurrent writers cannot overwrite each other's column.
 */
@Entity
@Table(name = "api_key")
@TenantScoped
public class ApiKey {

    /** Minimum interval between two persisted {@code last_used_at} updates, to avoid a write per request. */
    public static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private TenantId tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "key_hash", nullable = false, updatable = false, length = 64)
    private String keyHash;

    @Column(name = "prefix", nullable = false, updatable = false, length = 8)
    private String prefix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
    }

    public ApiKey(TenantId tenantId, String name, String keyHash, String prefix, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.name = Objects.requireNonNull(name, "name");
        this.keyHash = Objects.requireNonNull(keyHash, "keyHash");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    /** Whether a usage at {@code now} should be persisted, given {@link #LAST_USED_RESOLUTION}. */
    public boolean isUsageStaleAt(Instant now) {
        return lastUsedAt == null || !lastUsedAt.plus(LAST_USED_RESOLUTION).isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public TenantId getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getPrefix() {
        return prefix;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
