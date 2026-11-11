package io.pulseboard.core.application.alert;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-instance, in-memory circuit breaker keyed by tenant and receiver host, so one tenant's failing endpoint never
 * postpones another tenant's deliveries to the same (shared) provider host. After {@code hostFailureThreshold}
 * consecutive failures deliveries of that tenant to that host are postponed for {@code hostPause}; one success
 * closes it again. The counter is kept while the pause runs out, so the first attempt afterwards behaves like a
 * probe: a failure opens the breaker again immediately. A dead receiver therefore costs the dispatcher a handful of
 * timeouts, not one per event.
 *
 * <p>The callers decide what counts as a failure: transport errors, 408 and 5xx do, 429 and other 4xx do not. The
 * map holds at most {@value #MAX_ENTRIES} entries and one tenant at most {@value #MAX_ENTRIES_PER_TENANT} of them
 * (least recently used goes first, running pauses are never evicted), and entries that are idle for
 * {@value #IDLE_MINUTES}
 * minutes and not paused are forgotten, so it cannot grow without bound.
 */
@Component
public class HostCircuitBreaker {

    static final int MAX_ENTRIES = 10_000;
    static final int MAX_ENTRIES_PER_TENANT = 100;
    static final long IDLE_MINUTES = 10;
    private static final Duration IDLE = Duration.ofMinutes(IDLE_MINUTES);

    private static final class State {
        private final TenantId tenantId;
        private int failures;
        private Instant pausedUntil = Instant.EPOCH;
        private Instant touched;

        private State(TenantId tenantId) {
            this.tenantId = tenantId;
        }
    }

    private final AlertDispatchPolicy policy;
    private final Clock clock;
    private final int maxEntries;
    private final int maxEntriesPerTenant;
    private final Map<String, State> entries = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public HostCircuitBreaker(AlertDispatchPolicy policy, Clock clock) {
        this(policy, clock, MAX_ENTRIES, MAX_ENTRIES_PER_TENANT);
    }

    HostCircuitBreaker(AlertDispatchPolicy policy, Clock clock, int maxEntries, int maxEntriesPerTenant) {
        this.policy = policy;
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.maxEntriesPerTenant = maxEntriesPerTenant;
    }

    /** @return the end of the pause when the tenant's deliveries to the host are paused now, otherwise null */
    public synchronized Instant pausedUntil(TenantId tenantId, String hostKey) {
        State state = entries.get(key(tenantId, hostKey));
        if (state == null) {
            return null;
        }
        Instant now = clock.instant();
        if (state.pausedUntil.isAfter(now)) {
            return state.pausedUntil;
        }
        if (state.touched.plus(IDLE).isBefore(now)) {
            entries.remove(key(tenantId, hostKey));
        }
        return null;
    }

    public synchronized void recordFailure(TenantId tenantId, String hostKey) {
        Instant now = clock.instant();
        String key = key(tenantId, hostKey);
        State state = entries.get(key);
        if (state == null) {
            if (!makeRoom(tenantId, now)) {
                return; // every slot the tenant may use holds a running pause: this key stays untracked
            }
            state = new State(tenantId);
            entries.put(key, state);
        }
        if (state.touched != null && state.pausedUntil.isBefore(now) && state.touched.plus(IDLE).isBefore(now)) {
            state.failures = 0; // an old failure streak is forgotten
        }
        state.touched = now;
        state.failures++;
        if (state.failures >= policy.hostFailureThreshold()) {
            state.pausedUntil = now.plus(policy.hostPause());
        }
    }

    public synchronized void recordSuccess(TenantId tenantId, String hostKey) {
        entries.remove(key(tenantId, hostKey));
    }

    /** Number of tracked (tenant, host) pairs. */
    synchronized int size() {
        return entries.size();
    }

    /**
     * Frees a slot for a new key of the tenant. A running pause is never evicted to make room: a tenant over its own
     * cap loses its own least recently used entry that is not paused, and when the whole map is full the least
     * recently used unpaused entry of any tenant goes. If only paused entries are left, the new key is not tracked
     * (the breaker fails open for that key only), so no tenant can lift another tenant's pause.
     *
     * @return whether a slot is available
     */
    private boolean makeRoom(TenantId tenantId, Instant now) {
        int own = 0;
        for (State state : entries.values()) {
            if (state.tenantId.equals(tenantId)) {
                own++;
            }
        }
        if (own >= maxEntriesPerTenant && !evictOldestUnpaused(tenantId, now)) {
            return false;
        }
        return entries.size() < maxEntries || evictOldestUnpaused(null, now);
    }

    /** Removes the least recently used entry that is not paused; {@code tenantId} restricts it, null means any. */
    private boolean evictOldestUnpaused(TenantId tenantId, Instant now) {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next();
            if ((tenantId == null || state.tenantId.equals(tenantId)) && !state.pausedUntil.isAfter(now)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static String key(TenantId tenantId, String hostKey) {
        return tenantId.value() + "|" + hostKey;
    }
}
