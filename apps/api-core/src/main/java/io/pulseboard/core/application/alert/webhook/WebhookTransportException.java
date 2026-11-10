package io.pulseboard.core.application.alert.webhook;

/**
 * No HTTP response was received. The message is a short fixed phrase naming the kind of failure; it never contains
 * the URL, a host name or text from the other side.
 */
public class WebhookTransportException extends Exception {

    private static final long serialVersionUID = 1L;

    public WebhookTransportException(String reason) {
        super(reason);
    }
}
