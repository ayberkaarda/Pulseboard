package io.pulseboard.core.application.auth;

import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.util.Objects;

/**
 * Limits on the anonymous authentication endpoints.
 *
 * @param perClient shared bucket of register, login and refresh requests per client address
 * @param perAccount login attempts per tenant slug and email, checked before any password hashing
 */
public record AuthThrottlingPolicy(RateLimitPolicy perClient, RateLimitPolicy perAccount) {

    /** Rate-limit namespace of the per-client bucket. */
    public static final String CLIENT_NAMESPACE = "auth-client";
    /** Rate-limit namespace of the per-account bucket. */
    public static final String ACCOUNT_NAMESPACE = "auth-account";

    public AuthThrottlingPolicy {
        Objects.requireNonNull(perClient, "perClient");
        Objects.requireNonNull(perAccount, "perAccount");
    }
}
