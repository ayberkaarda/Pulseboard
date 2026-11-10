package io.pulseboard.core.application.common;

/** The request conflicts with existing state, for example a duplicate unique name. */
public class ResourceConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceConflictException(String message) {
        super(message);
    }
}
