package io.pulseboard.core.api;

import static io.pulseboard.core.support.RawHttp.postJson;
import static io.pulseboard.core.support.RawHttp.unknownLoginBody;
import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/**
 * Per-client throttle of the anonymous authentication endpoints, in its own context with capacity 3. Requests
 * come from loopback, which is not a trusted proxy, so X-Forwarded-For must be ignored here.
 */
@TestPropertySource(properties = {
    "pulseboard.rate-limit.auth.client-capacity=3",
    "pulseboard.rate-limit.auth.client-refill-period=1h"
})
class AuthClientRateLimitIntegrationTest extends IntegrationTest {

    private static final String INVALID_CREDENTIALS = "Invalid credentials";

    @Value("${local.server.port}")
    private int port;

    @Value("${server.tomcat.remoteip.internal-proxies}")
    private String trustedProxies;

    @Test
    void everyRoutedSpellingSharesOneBucketAndSpoofedForwardedForIsIgnored() {
        // Three spellings that Spring MVC routes to the login handler, each with a different forged client address.
        List<String> routedLogins = List.of("/api/v1/auth/login", "/api/v1/%61uth/login", "/api/v1/auth/%6Cogin");
        for (int i = 0; i < routedLogins.size(); i++) {
            HttpResponse<String> response = postJson(port, routedLogins.get(i), unknownLoginBody(),
                    "X-Forwarded-For", "198.51.100." + (i + 1));
            assertThat(response.statusCode()).as(routedLogins.get(i)).isEqualTo(401);
            assertThat(response.body()).as(routedLogins.get(i)).contains(INVALID_CREDENTIALS);
        }

        // The bucket is empty for every endpoint and spelling; a broken body shows the check precedes parsing.
        for (String path : List.of("/api/v1/auth/register", "/api/v1/%61uth/register", "/api/v1/auth/%72efresh",
                "/api/v1/auth/logout", "/api/v1/auth/%6Cogout",
                "/api/v1/auth/refresh", "/api/v1/auth/login")) {
            HttpResponse<String> response = postJson(port, path, "{not json", "X-Forwarded-For", "203.0.113.99");
            assertThat(response.statusCode()).as(path).isEqualTo(429);
            assertThat(response.headers().firstValue("Content-Type")).as(path).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/problem+json"));
            assertThat(response.headers().firstValue("Retry-After")).as(path).hasValueSatisfying(
                    value -> assertThat(Long.parseLong(value)).isBetween(1L, 1200L));
            assertThat(response.body()).as(path).contains("urn:pulseboard:problem:rate-limited");
        }
    }

    @Test
    void spellingsThatDoNotRouteToTheHandlerNeverRunLogin() {
        for (String path : List.of("/API/V1/AUTH/LOGIN", "/api/v1/auth/login/", "//api/v1/auth/login",
                "/api/v1/auth/login;x=1", "/api/v1/auth/..;/auth/login")) {
            HttpResponse<String> response = postJson(port, path, unknownLoginBody());
            assertThat(response.statusCode()).as(path).isIn(400, 401, 404);
            assertThat(response.body()).as(path).doesNotContain(INVALID_CREDENTIALS);
        }
    }

    @Test
    void defaultTrustedProxiesAreDockerPrivateRangesButNotLoopbackOrPublic() {
        Pattern trusted = Pattern.compile(trustedProxies);

        assertThat(List.of("172.18.0.5", "10.0.3.7", "192.168.65.1")).allMatch(ip -> trusted.matcher(ip).matches());
        assertThat(List.of("127.0.0.1", "0:0:0:0:0:0:0:1", "203.0.113.7", "172.32.0.1", "8.8.8.8"))
                .noneMatch(ip -> trusted.matcher(ip).matches());
    }

    @Test
    void nonAuthEndpointsAreNotThrottledByTheAuthBucket() {
        for (int i = 0; i < 5; i++) {
            client.get().uri("/api/v1/services").exchange().expectStatus().isUnauthorized();
        }
    }
}
