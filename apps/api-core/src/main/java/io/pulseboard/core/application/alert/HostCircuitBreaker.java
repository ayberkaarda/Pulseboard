package io.pulseboard.core.application.alert;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-instance, in-memory circuit breaker keyed by receiver host. After {@code hostFailureThreshold} consecutive
 * failures deliveries to that host are postponed for {@code hostPause}; one success closes it again. The counter
 * is kept while the pause runs out, so the first attempt afterwards behaves like a probe: a failure opens the
 * breaker again immediately. A dead receiver therefore costs the dispatcher a handful of timeouts, not one per event.
 */
@Component
public class HostCircuitBreaker {

    private static final int MAX_HOSTS = 1_000;

    private static final class State {
        private int failures;
        private Instant pausedUntil = Instant.EPOCH;
    }

    private final AlertDispatchPolicy policy;
    private final Clock clock;
    private final Map<String, State> hosts = new ConcurrentHashMap<>();

    public HostCircuitBreaker(AlertDispatchPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    /** @return the end of the pause when deliveries to the host are paused now, otherwise {@code null} */
    public Instant pausedUntil(String hostKey) {
        State state = hosts.get(hostKey);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.pausedUntil.isAfter(clock.instant()) ? state.pausedUntil : null;
        }
    }

    public void recordFailure(String hostKey) {
        if (hosts.size() >= MAX_HOSTS && !hosts.containsKey(hostKey)) {
            Instant now = clock.instant();
            hosts.values().removeIf(state -> {
                synchronized (state) {
                    return !state.pausedUntil.isAfter(now) && state.failures < policy.hostFailureThreshold();
                }
            });
            if (hosts.size() >= MAX_HOSTS) {
                hosts.clear();
            }
        }
        State state = hosts.computeIfAbsent(hostKey, key -> new State());
        synchronized (state) {
            state.failures++;
            if (state.failures >= policy.hostFailureThreshold()) {
                state.pausedUntil = clock.instant().plus(policy.hostPause());
            }
        }
    }

    public void recordSuccess(String hostKey) {
        hosts.remove(hostKey);
    }
}
