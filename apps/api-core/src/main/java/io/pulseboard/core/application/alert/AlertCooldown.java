package io.pulseboard.core.application.alert;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Per-rule quiet period after a firing. */
public interface AlertCooldown {

    /**
     * Starts the quiet period atomically, unless one is running.
     *
     * @return the token of the quiet period when this call started it (the caller may fire), empty when the rule is
     *     cooling down
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the store cannot be reached
     */
    Optional<String> tryStart(UUID ruleId, Duration duration);

    /**
     * Ends the quiet period early, for a firing that did not result in an event, so a retry can fire. Only the period
     * with this {@code token} is ended: when it already ran out and another caller started a new one, that one stays.
     * Best effort: a store that cannot be reached leaves the period to run out.
     */
    void release(UUID ruleId, String token);
}
