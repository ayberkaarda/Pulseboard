package io.pulseboard.core.application.alert.webhook;

import java.net.URI;

/**
 * A syntactically acceptable webhook URL.
 *
 * @param uri the URL, normalised (lower-case scheme and host, no trailing dot, no fragment); the sender uses
 *     exactly this URI
 * @param host lower-case ASCII host name without a trailing dot
 * @param port explicit or scheme default port
 */
public record WebhookTarget(URI uri, String host, int port) {

    /** What identifies the endpoint in logs and in the per-host circuit breaker; never the path or query. */
    public String hostKey() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return "WebhookTarget[" + hostKey() + "]";
    }
}
