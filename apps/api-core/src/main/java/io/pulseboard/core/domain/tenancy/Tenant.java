package io.pulseboard.core.domain.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A customer organisation and the root of tenant isolation. */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "slug", nullable = false, updatable = false, length = 63)
    private String slug;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 16)
    private Plan plan;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {
    }

    public Tenant(String slug, String name, Plan plan, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.slug = Objects.requireNonNull(slug, "slug");
        this.name = Objects.requireNonNull(name, "name");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public TenantId tenantId() {
        return TenantId.of(id);
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public Plan getPlan() {
        return plan;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
