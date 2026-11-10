package io.pulseboard.core.domain.identity;

import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** A human user belonging to exactly one tenant. Emails are unique per tenant and stored lower-case. */
@Entity
@Table(name = "app_user")
@TenantScoped
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private TenantId tenantId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(TenantId tenantId, String email, String passwordHash, Role role, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.email = normalizeEmail(email);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.role = Objects.requireNonNull(role, "role");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Canonical form used for storage and lookups. */
    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public TenantId getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
