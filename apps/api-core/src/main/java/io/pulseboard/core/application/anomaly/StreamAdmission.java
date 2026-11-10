package io.pulseboard.core.application.anomaly;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Counts the open live streams per tenant and in total. A permit is taken before the stream is opened and must be
 * closed exactly when the connection ends; closing is idempotent, so no path can release a slot twice.
 */
@Component
public class StreamAdmission {

    private final StreamPolicy policy;
    private final Object lock = new Object();
    private final Map<TenantId, Integer> perTenant = new HashMap<>();
    private int total;

    public StreamAdmission(StreamPolicy policy) {
        this.policy = policy;
    }

    public StreamPolicy policy() {
        return policy;
    }

    /** @throws StreamLimitExceededException when the tenant or the instance is at its limit */
    public Permit acquire(TenantId tenantId) {
        synchronized (lock) {
            if (total >= policy.maxTotal()) {
                throw new StreamLimitExceededException("The server holds the maximum number of live streams");
            }
            int open = perTenant.getOrDefault(tenantId, 0);
            if (open >= policy.maxPerTenant()) {
                throw new StreamLimitExceededException("The tenant already holds the maximum number of live streams");
            }
            perTenant.put(tenantId, open + 1);
            total++;
        }
        return new Permit(tenantId);
    }

    /** Streams currently open across all tenants. */
    public int openStreams() {
        synchronized (lock) {
            return total;
        }
    }

    private void release(TenantId tenantId) {
        synchronized (lock) {
            perTenant.computeIfPresent(tenantId, (tenant, open) -> open <= 1 ? null : open - 1);
            total--;
        }
    }

    /** One open stream; closing it frees the slot. */
    public final class Permit implements AutoCloseable {

        private final TenantId tenantId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(TenantId tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release(tenantId);
            }
        }
    }
}
