package io.pulseboard.core.domain.identity;

import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One link of a rotating refresh-token chain. All tokens issued from one login share a {@code familyId}; a
 * token can be exchanged exactly once, after which it is revoked and points to its successor.
 */
@Entity
@Table(name = "refresh_token")
@TenantScoped
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private TenantId tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {
    }

    public RefreshToken(TenantId tenantId, UUID userId, UUID familyId, String tokenHash, Instant issuedAt,
            Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** Marks this token as consumed and records the token that replaces it. */
    public void rotateTo(RefreshToken successor, Instant now) {
        Objects.requireNonNull(successor, "successor");
        if (isRevoked()) {
            throw new IllegalStateException("A revoked refresh token cannot be rotated");
        }
        if (!successor.familyId.equals(familyId) || !successor.tenantId.equals(tenantId)) {
            throw new IllegalArgumentException("Successor must belong to the same family and tenant");
        }
        revokedAt = Objects.requireNonNull(now, "now");
        replacedBy = successor.id;
    }

    public UUID getId() {
        return id;
    }

    public TenantId getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }
}
