package io.pulseboard.core.application.tenancy;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Tenant of the current request, resolved from the authenticated JWT or API key by the tenant filter. It is
 * bound once per request; rebinding to a different tenant is rejected.
 */
@Component
@RequestScope
public class TenantContext {

    private TenantId tenantId;

    public void bind(TenantId tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("tenant must not be null");
        }
        if (tenantId != null && !tenantId.equals(tenant)) {
            throw new IllegalStateException("Tenant context is already bound to another tenant");
        }
        tenantId = tenant;
    }

    public Optional<TenantId> currentTenantId() {
        return Optional.ofNullable(tenantId);
    }

    /** Returns the bound tenant or fails when the request is not tenant-authenticated. */
    public TenantId requireTenantId() {
        if (tenantId == null) {
            throw new IllegalStateException("No tenant is bound to the current request");
        }
        return tenantId;
    }
}
