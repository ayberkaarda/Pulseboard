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

/**
 * Definition of a metric reported by a service (for example {@code latency_p95_ms}). The tenant is copied from
 * the owning service; the database pins both together with a composite foreign key.
 */
@Entity
@Table(name = "metric_definition")
@TenantScoped
public class MetricDefinition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private TenantId tenantId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregation", nullable = false, length = 8)
    private Aggregation aggregation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MetricDefinition() {
    }

    public MetricDefinition(Service service, String name, String unit, Aggregation aggregation, Instant createdAt) {
        Objects.requireNonNull(service, "service");
        this.id = UUID.randomUUID();
        this.tenantId = service.getTenantId();
        this.serviceId = service.getId();
        this.name = Objects.requireNonNull(name, "name");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.aggregation = Objects.requireNonNull(aggregation, "aggregation");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID getId() {
        return id;
    }

    public TenantId getTenantId() {
        return tenantId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getName() {
        return name;
    }

    public String getUnit() {
        return unit;
    }

    public Aggregation getAggregation() {
        return aggregation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
