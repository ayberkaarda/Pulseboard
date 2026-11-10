package io.pulseboard.core.api;

import static io.pulseboard.core.support.RawHttp.postJson;
import static io.pulseboard.core.support.RawHttp.unknownLoginBody;
import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/**
 * Behind a trusted reverse proxy (here: the test client on loopback plays the proxy) the per-client bucket is
 * keyed by the forwarded client address. Different clients must not share a bucket, and a client cannot escape
 * its bucket by prepending forged addresses to X-Forwarded-For (the proxy appends the real peer last).
 */
@TestPropertySource(properties = {
    "pulseboard.rate-limit.auth.client-capacity=2",
    "pulseboard.rate-limit.auth.client-refill-period=1h",
    "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1"
})
class AuthTrustedProxyRateLimitIntegrationTest extends IntegrationTest {

    private static final String CLIENT_A = "203.0.113.10";
    private static final String CLIENT_B = "203.0.113.20";

    @Value("${local.server.port}")
    private int port;

    @Test
    void clientsBehindTheProxyHaveSeparateBucketsAndCannotForgeTheirWayOut() {
        assertThat(login(CLIENT_A)).isEqualTo(401);
        assertThat(login(CLIENT_A)).isEqualTo(401);
        assertThat(login(CLIENT_A)).isEqualTo(429);

        // Another client behind the same proxy is unaffected.
        assertThat(login(CLIENT_B)).isEqualTo(401);
        // Client A prepends a fresh forged address; the proxy-appended real address (last entry) still counts.
        assertThat(login("198.51.100.77, " + CLIENT_A)).isEqualTo(429);
        // The proxy's own address (no forwarded header) is a separate bucket too.
        assertThat(postJson(port, "/api/v1/auth/login", unknownLoginBody()).statusCode()).isEqualTo(401);
    }

    private int login(String forwardedFor) {
        return postJson(port, "/api/v1/auth/login", unknownLoginBody(), "X-Forwarded-For", forwardedFor)
                .statusCode();
    }
}
