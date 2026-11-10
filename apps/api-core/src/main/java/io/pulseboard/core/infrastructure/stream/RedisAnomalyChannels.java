package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.domain.tenancy.TenantId;

/** Names of the Redis pub/sub channels used for live anomalies. */
final class RedisAnomalyChannels {

    private static final String PREFIX = "anomalies:";

    private RedisAnomalyChannels() {
    }

    /** One channel per tenant: {@code anomalies:{tenantId}}. */
    static String forTenant(TenantId tenantId) {
        return PREFIX + tenantId.value();
    }
}
