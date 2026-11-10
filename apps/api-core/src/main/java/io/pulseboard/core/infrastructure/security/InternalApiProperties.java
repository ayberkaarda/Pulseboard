package io.pulseboard.core.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the internal (service to service) endpoints, bound from {@code pulseboard.internal.*}.
 *
 * <p>The shared secret comes from {@code INTERNAL_SHARED_SECRET}; the application refuses to start when it is
 * missing, blank or shorter than 32 characters.
 *
 * @param sharedSecret HMAC-SHA256 key shared with the analytics service
 * @param maxBodyBytes largest accepted request body; larger bodies are rejected before they are hashed
 */
@ConfigurationProperties("pulseboard.internal")
public record InternalApiProperties(String sharedSecret, int maxBodyBytes) {

    public static final int MIN_SECRET_LENGTH = 32;

    public InternalApiProperties {
        if (sharedSecret == null || sharedSecret.isBlank() || sharedSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("pulseboard.internal.shared-secret (INTERNAL_SHARED_SECRET) must be "
                    + "set to at least " + MIN_SECRET_LENGTH + " characters");
        }
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("pulseboard.internal.max-body-bytes must be positive");
        }
    }

    /** Hides the secret when the properties are logged or rendered. */
    @Override
    public String toString() {
        return "InternalApiProperties[maxBodyBytes=" + maxBodyBytes + "]";
    }
}
