package io.pulseboard.core.application.common;

/** The resource was changed by someone else since the version the caller based its change on. */
public class StaleVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleVersionException(String message) {
        super(message);
    }
}
