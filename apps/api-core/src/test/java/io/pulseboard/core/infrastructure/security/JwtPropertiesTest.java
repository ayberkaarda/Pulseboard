package io.pulseboard.core.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Token lifetimes are validated at startup; the session lifetime may not undercut the refresh token's. */
class JwtPropertiesTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void sessionLifetimeEqualToOrLongerThanTheRefreshTokenIsAccepted() {
        assertThat(properties(Duration.ofDays(7), Duration.ofDays(7)).refreshFamilyTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(properties(Duration.ofDays(7), Duration.ofDays(30)).refreshFamilyTtl())
                .isEqualTo(Duration.ofDays(30));
    }

    @Test
    void sessionLifetimeShorterThanTheRefreshTokenIsRejected() {
        assertThatThrownBy(() -> properties(Duration.ofDays(7), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh-family-ttl");
    }

    @Test
    void missingZeroOrNegativeSessionLifetimeIsRejected() {
        assertThatThrownBy(() -> properties(Duration.ofDays(7), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofDays(7), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofDays(7), Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JwtProperties properties(Duration refreshTokenTtl, Duration refreshFamilyTtl) {
        return new JwtProperties(SECRET, "issuer", "audience", Duration.ofMinutes(15), refreshTokenTtl,
                refreshFamilyTtl);
    }
}
