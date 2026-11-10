package io.pulseboard.core.application.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.application.anomaly.StreamAdmission.Permit;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class StreamAdmissionTest {

    private static final TenantId A = TenantId.of(UUID.randomUUID());
    private static final TenantId B = TenantId.of(UUID.randomUUID());

    private static StreamAdmission admission(int perTenant, int total) {
        return new StreamAdmission(new StreamPolicy(perTenant, total, Duration.ofSeconds(30), Duration.ofMinutes(30)));
    }

    @Test
    void aTenantIsLimitedWhileOthersAreNot() {
        StreamAdmission admission = admission(2, 10);
        admission.acquire(A);
        admission.acquire(A);

        assertThatThrownBy(() -> admission.acquire(A)).isInstanceOf(StreamLimitExceededException.class);
        assertThat(admission.acquire(B)).isNotNull();
        assertThat(admission.openStreams()).isEqualTo(3);
    }

    @Test
    void theInstanceLimitAppliesAcrossTenants() {
        StreamAdmission admission = admission(5, 2);
        admission.acquire(A);
        admission.acquire(B);

        assertThatThrownBy(() -> admission.acquire(TenantId.of(UUID.randomUUID())))
                .isInstanceOf(StreamLimitExceededException.class);
    }

    @Test
    void closingAPermitFreesTheSlotExactlyOnce() {
        StreamAdmission admission = admission(1, 5);
        Permit first = admission.acquire(A);

        first.close();
        first.close();
        first.close();
        Permit second = admission.acquire(A);

        assertThat(admission.openStreams()).isEqualTo(1);
        assertThatThrownBy(() -> admission.acquire(A)).isInstanceOf(StreamLimitExceededException.class);
        second.close();
        assertThat(admission.openStreams()).isZero();
    }

    @Test
    void parallelAcquisitionsNeverExceedTheLimits() throws Exception {
        StreamAdmission admission = admission(3, 100);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                attempts.add(() -> {
                    try {
                        admission.acquire(A);
                        return true;
                    } catch (StreamLimitExceededException ex) {
                        return false;
                    }
                });
            }
            long granted = 0;
            for (Future<Boolean> result : pool.invokeAll(attempts)) {
                granted += result.get() ? 1 : 0;
            }
            assertThat(granted).isEqualTo(3);
            assertThat(admission.openStreams()).isEqualTo(3);
        }
    }
}
