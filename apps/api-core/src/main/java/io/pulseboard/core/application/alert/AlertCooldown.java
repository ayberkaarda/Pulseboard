package io.pulseboard.core.application.alert;

import java.time.Duration;
import java.util.UUID;

/** Per-rule quiet period after a firing. */
public interface AlertCooldown {

    /**
     * Starts the quiet period atomically, unless one is running.
     *
     * @return {@code true} when this call started it (the caller may fire), {@code false} when the rule is cooling
     *     down
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the store cannot be reached
     */
    boolean tryStart(UUID ruleId, Duration duration);

    /**
     * Ends the quiet period early, for a firing that did not result in an event, so a retry can fire. Best effort:
     * a store that cannot be reached leaves the period to run out.
     */
    void release(UUID ruleId);
}
