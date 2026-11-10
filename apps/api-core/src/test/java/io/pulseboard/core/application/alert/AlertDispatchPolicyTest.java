package io.pulseboard.core.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertDispatchPolicyTest {

    private static AlertDispatchPolicy policy(Duration lease, Duration timeout, List<Duration> backoff) {
        return new AlertDispatchPolicy(10, lease, 4, backoff, 5, Duration.ofSeconds(60), timeout);
    }

    @Test
    void theBackoffGrowsPerFailedAttemptAndTheLastValueRepeats() {
        AlertDispatchPolicy policy = policy(Duration.ofSeconds(60), Duration.ofSeconds(20),
                List.of(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(300)));

        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofSeconds(300));
        assertThat(policy.backoffAfter(9)).isEqualTo(Duration.ofSeconds(300));
        assertThat(policy.backoffAfter(0)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theLeaseMustOutlastTheDeliveryTimeout() {
        assertThatThrownBy(() -> policy(Duration.ofSeconds(20), Duration.ofSeconds(20), List.of(Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(Duration.ofSeconds(60), Duration.ofSeconds(20), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
