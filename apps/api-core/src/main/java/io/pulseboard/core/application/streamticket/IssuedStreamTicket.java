package io.pulseboard.core.application.streamticket;

/**
 * A freshly issued ticket. The value exists only here and in the response; it is never stored or logged.
 *
 * @param value the opaque ticket
 * @param expiresInSeconds seconds the ticket can still be used
 */
public record IssuedStreamTicket(String value, long expiresInSeconds) {

    @Override
    public String toString() {
        return "IssuedStreamTicket[expiresInSeconds=" + expiresInSeconds + "]";
    }
}
