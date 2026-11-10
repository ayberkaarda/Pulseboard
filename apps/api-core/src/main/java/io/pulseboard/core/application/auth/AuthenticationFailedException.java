package io.pulseboard.core.application.auth;

/**
 * Credentials (password or refresh token) were rejected. The message is deliberately generic so callers cannot
 * distinguish an unknown tenant, an unknown user, a wrong password or a reused token.
 */
public class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
