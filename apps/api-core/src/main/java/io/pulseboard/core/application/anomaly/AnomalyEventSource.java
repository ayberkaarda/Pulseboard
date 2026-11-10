package io.pulseboard.core.application.anomaly;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.function.Consumer;

/** Source of the anomaly messages published for one tenant. */
public interface AnomalyEventSource {

    /**
     * Starts delivering the messages published for {@code tenantId} (and for no other tenant) to {@code listener}
     * as JSON. The listener must not block. Closing the subscription stops the delivery and releases the
     * underlying subscription when it was the last one of the tenant.
     *
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the message bus is not
     *     reachable
     */
    Subscription subscribe(TenantId tenantId, Consumer<String> listener);

    /** Handle of an active subscription; closing it more than once is harmless. */
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}
