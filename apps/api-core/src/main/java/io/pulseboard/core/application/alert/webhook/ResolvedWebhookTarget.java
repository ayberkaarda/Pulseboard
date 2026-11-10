package io.pulseboard.core.application.alert.webhook;

import java.net.InetAddress;
import java.util.List;

/**
 * A webhook target together with the addresses it resolved to when it was checked. The connection must be made to
 * one of these addresses, never to a fresh lookup of the name, so a DNS answer that changes between the check and
 * the connect cannot redirect the request.
 */
public record ResolvedWebhookTarget(WebhookTarget target, List<InetAddress> addresses) {

    public ResolvedWebhookTarget {
        addresses = List.copyOf(addresses);
    }
}
