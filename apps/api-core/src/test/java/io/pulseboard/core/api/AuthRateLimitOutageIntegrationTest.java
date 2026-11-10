package io.pulseboard.core.api;

import static io.pulseboard.core.support.RawHttp.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.pulseboard.core.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;

/**
 * Fail-closed behaviour: with the rate-limit store down, every spelling of the auth endpoints answers 503 before
 * any password hashing or database work. Runs in its own context and stops that context's Redis container.
 */
@TestPropertySource(properties = "pulseboard.rate-limit.auth.client-capacity=50")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthRateLimitOutageIntegrationTest extends IntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redisContainer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @Test
    void authEndpointsFailClosedForEverySpellingWhenRedisIsDown() {
        redisContainer.stop();
        clearInvocations(passwordEncoder);

        for (String path : List.of("/api/v1/auth/register", "/api/v1/%61uth/register")) {
            HttpResponse<String> response = postJson(port, path, """
                    {"tenantSlug":"outage-tenant","tenantName":"Outage","email":"o@example.com",
                     "password":"correct-horse-battery-staple"}
                    """);
            assertThat(response.statusCode()).as(path).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).as(path).isPresent();
            assertThat(response.body()).as(path).contains("urn:pulseboard:problem:unavailable");
        }
        for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/%6Cogin", "/api/v1/auth/refresh")) {
            HttpResponse<String> response = postJson(port, path,
                    "{\"tenantSlug\":\"outage-tenant\",\"email\":\"o@example.com\",\"password\":\"x-password\","
                            + "\"refreshToken\":\"x\"}");
            assertThat(response.statusCode()).as(path).isEqualTo(503);
        }

        verify(passwordEncoder, never()).encode(any());
        verify(passwordEncoder, never()).matches(any(), anyString());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant WHERE slug = 'outage-tenant'",
                Integer.class)).isZero();
    }
}
