package io.pulseboard.core.application.anomaly;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.UUID;

/**
 * The only parts of a notification that are used: which row, and the tenant the sender claims owns it. Everything
 * else in the message is ignored; the database is the source of truth.
 */
public record NotifiedAnomaly(UUID id, TenantId tenantId) {
}
