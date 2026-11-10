package io.pulseboard.core.application.alert.webhook;

import java.util.Map;

/** Sends one HTTP POST to a resolved, already checked webhook target. */
public interface WebhookSender {

    /**
     * Posts the body. The connection is made to one of the addresses of the target (never to a fresh DNS answer),
     * redirects are not followed and the response body is neither read nor kept.
     *
     * @return the HTTP status code of the response
     * @throws WebhookTransportException when no response was received
     */
    int post(ResolvedWebhookTarget target, Map<String, String> headers, byte[] body)
            throws WebhookTransportException;
}
