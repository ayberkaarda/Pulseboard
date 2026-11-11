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
 * @param connectTimeout limit for establishing the connection (and for the TLS handshake)
 * @param readTimeout limit for waiting on the response, per read
 * @param totalTimeout wall-clock limit for the whole call, from the first connect to the status line and headers;
 *     enforced by a watchdog that aborts the connection
 */
public record WebhookPolicy(
        String signingSecret,
        boolean allowHttp,
        boolean allowPrivateTargets,
        Set<Integer> allowedPorts,
        Duration connectTimeout,
        Duration readTimeout,
        Duration totalTimeout) {

    /** Default wall-clock limit of a call when none is given. */
    public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(20);

    public WebhookPolicy {
        Objects.requireNonNull(signingSecret, "signingSecret");
        allowedPorts = Set.copyOf(allowedPorts);
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
    }

    public WebhookPolicy(String signingSecret, boolean allowHttp, boolean allowPrivateTargets,
            Set<Integer> allowedPorts, Duration connectTimeout, Duration readTimeout) {
        this(signingSecret, allowHttp, allowPrivateTargets, allowedPorts, connectTimeout, readTimeout,
                DEFAULT_TOTAL_TIMEOUT);
    }

    /** Whether webhook delivery can sign requests. */
    public boolean configured() {
        return !signingSecret.isEmpty();
    }

    @Override
    public String toString() {
        return "WebhookPolicy[allowHttp=" + allowHttp + ", allowPrivateTargets=" + allowPrivateTargets
                + ", allowedPorts=" + allowedPorts + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", totalTimeout=" + totalTimeout + "]";
    }
}
