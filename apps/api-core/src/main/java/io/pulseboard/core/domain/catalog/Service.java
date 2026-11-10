package io.pulseboard.core.domain.catalog;

import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A monitored service of a tenant. Names are unique per tenant. */
@Entity
@Table(name = "service")
@TenantScoped
public class Service {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private TenantId tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 16)
    private Environment environment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Service() {
    }

    public Service(TenantId tenantId, String name, Environment environment, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.name = Objects.requireNonNull(name, "name");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
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

    public Environment getEnvironment() {
        return environment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
