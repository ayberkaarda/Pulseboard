package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.application.anomaly.AnomalyService;
import io.pulseboard.core.domain.anomaly.AnomalyQuery;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.AnomalyIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Listing bounds finer than the stored microsecond keep the exact meaning of {@code [from, to)}. */
class AnomalyNanosecondBoundsIntegrationTest extends AnomalyIntegrationTest {

    private static final Instant T = Instant.parse("2026-03-01T00:00:00.123456Z");
    private static final long[] OFFSETS = {-600, -500, -499, -100, -1, 0, 1, 100, 499, 500, 600, 1000};

    @Autowired
    private AnomalyService service;

    private List<UUID> ids(TenantFixture tenant, Instant from, Instant to) {
        return service.list(TenantId.of(tenant.tenantId()), new AnomalyQuery(null, null, null, null, from, to), 0, 100)
                .items().stream().map(a -> a.id()).toList();
    }

    @Test
    void aLowerBoundIncludesTheRowExactlyWhenTheRowIsNotBeforeIt() {
        TenantFixture tenant = newTenant();
        StoredAnomaly row = insertAnomaly(tenant, T, "HIGH");

        for (long offset : OFFSETS) {
            Instant from = T.plusNanos(offset);
            boolean included = !T.isBefore(from);
            assertThat(ids(tenant, from, null).contains(row.id()))
                    .as("from = T%+d ns", offset).isEqualTo(included);
        }
    }

    @Test
    void anUpperBoundIncludesTheRowExactlyWhenTheRowIsBeforeIt() {
        TenantFixture tenant = newTenant();
        StoredAnomaly row = insertAnomaly(tenant, T, "HIGH");

        for (long offset : OFFSETS) {
            Instant to = T.plusNanos(offset);
            boolean included = T.isBefore(to);
            assertThat(ids(tenant, null, to).contains(row.id()))
                    .as("to = T%+d ns", offset).isEqualTo(included);
        }
    }

    @Test
    void aRangeBetweenTwoStorableInstantsIsValidAndEmpty() {
        TenantFixture tenant = newTenant();
        insertAnomaly(tenant, T, "HIGH");

        assertThat(ids(tenant, T.plusNanos(100), T.plusNanos(600))).isEmpty();
        assertThat(ids(tenant, T.plusNanos(1000), T.plusNanos(1001))).isEmpty();
    }
}
