package io.pulseboard.core.application.alert.webhook;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Rules and limits for webhook targets and calls.
 *
 * @param signingSecret HMAC key of the signature header; empty when the webhook channel is not configured. Never
 *     part of {@link #toString()}.
 * @param allowHttp accept {@code http://} targets (tests and local development only)
 * @param allowPrivateTargets accept loopback and private addresses (tests and local development only)
 * @param allowedPorts ports a target may use
 * @param connectTimeout limit for establishing the connection
 * @param readTimeout limit for waiting on the response
 */
public record WebhookPolicy(
        String signingSecret,
        boolean allowHttp,
        boolean allowPrivateTargets,
        Set<Integer> allowedPorts,
        Duration connectTimeout,
        Duration readTimeout) {

    public WebhookPolicy {
        Objects.requireNonNull(signingSecret, "signingSecret");
        allowedPorts = Set.copyOf(allowedPorts);
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
    }

    /** Whether webhook delivery can sign requests. */
    public boolean configured() {
        return !signingSecret.isEmpty();
    }

    @Override
    public String toString() {
        return "WebhookPolicy[allowHttp=" + allowHttp + ", allowPrivateTargets=" + allowPrivateTargets
                + ", allowedPorts=" + allowedPorts + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
