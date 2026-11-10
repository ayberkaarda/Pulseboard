package io.pulseboard.core.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings bound from {@code pulseboard.security.jwt.*}. The signing secret comes from the
 * {@code JWT_SECRET} environment variable and must be at least 32 bytes (HS256 key length).
 */
@ConfigurationProperties("pulseboard.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("pulseboard.security.jwt.secret (JWT_SECRET) must be set to at least "
                    + MIN_SECRET_BYTES + " bytes");
        }
        requireText(issuer, "issuer");
        requireText(audience, "audience");
        requirePositive(accessTokenTtl, "access-token-ttl");
        requirePositive(refreshTokenTtl, "refresh-token-ttl");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("pulseboard.security.jwt." + name + " must be set");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("pulseboard.security.jwt." + name + " must be a positive duration");
        }
    }

    /** Hides the secret when the properties are logged or rendered. */
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", audience=" + audience + ", accessTokenTtl=" + accessTokenTtl
                + ", refreshTokenTtl=" + refreshTokenTtl + "]";
    }
}
