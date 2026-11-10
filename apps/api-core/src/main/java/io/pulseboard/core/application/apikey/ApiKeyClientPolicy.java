package io.pulseboard.core.application.apikey;

import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.util.Objects;

/**
 * Per-client limits for requests that present an API key, applied before the key is looked up.
 *
 * @param perClient every request carrying {@code X-Api-Key}, per client address
 * @param invalidPerClient failed API key authentications per client address; once exhausted, further requests
 *     with any key from that address are rejected before the cache or the database is consulted
 */
public record ApiKeyClientPolicy(RateLimitPolicy perClient, RateLimitPolicy invalidPerClient) {

    /** Rate-limit namespace of the per-client bucket. */
    public static final String CLIENT_NAMESPACE = "apikey-client";
    /** Rate-limit namespace of the per-client failed-authentication bucket. */
    public static final String INVALID_NAMESPACE = "apikey-invalid";

    public ApiKeyClientPolicy {
        Objects.requireNonNull(perClient, "perClient");
        Objects.requireNonNull(invalidPerClient, "invalidPerClient");
    }
}
