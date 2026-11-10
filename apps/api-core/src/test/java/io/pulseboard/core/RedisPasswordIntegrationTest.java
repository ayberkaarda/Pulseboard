package io.pulseboard.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The application works against a Redis that requires a password ({@code REDIS_PASSWORD}, bound to
 * {@code spring.data.redis.password}), as the Compose stack runs it: API key cache, token buckets and ingestion
 * all go through the authenticated connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(RedisPasswordIntegrationTest.PasswordProtectedRedisConfiguration.class)
class RedisPasswordIntegrationTest {

    private static final String REDIS_PASSWORD = PostgresTestcontainersConfiguration.randomPassword();

    @Autowired
    private RestTestClient client;

    @Autowired
    @Qualifier("passwordProtectedRedis")
    private GenericContainer<?> redis;

    @Test
    void ingestionAndQueriesWorkAgainstAPasswordProtectedRedis() throws Exception {
        Container.ExecResult anonymous = redis.execInContainer("redis-cli", "ping");
        assertThat(anonymous.getStdout() + anonymous.getStderr()).as("Redis rejects unauthenticated clients")
                .contains("NOAUTH");

        String slug = "t-" + PostgresTestcontainersConfiguration.randomPassword().substring(0, 12);
        TokenResponse owner = client.post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", slug, "tenantName", "Tenant", "email", "owner@example.com",
                        "password", "correct-horse-battery-staple"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TokenResponse.class).returnResult().getResponseBody();
        String bearer = "Bearer " + owner.accessToken();
        String serviceId = client.post().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "svc", "environment", "PROD"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id").toString();
        String metricId = client.post().uri("/api/v1/services/{id}/metrics", serviceId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "latency_ms", "unit", "ms", "aggregation", "AVG"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id").toString();
        String apiKey = client.post().uri("/api/v1/api-keys")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "agent"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("key").toString();

        Instant now = Instant.now();
        client.post().uri("/api/v1/ingest")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"points\":[{\"metricId\":\"" + metricId + "\",\"ts\":\"" + now + "\",\"value\":4}]}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.accepted").isEqualTo(1);
        client.get().uri("/api/v1/metrics/" + metricId + "/points?from=" + now.minusSeconds(60) + "&to="
                        + now.plusSeconds(60))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.points[0].value").isEqualTo(4.0);

        Container.ExecResult keys = redis.execInContainer("redis-cli", "--no-auth-warning", "-a", REDIS_PASSWORD,
                "--scan", "--pattern", "apikey:*");
        assertThat(keys.getStdout()).as("the API key cache entry lives in the protected Redis").contains("apikey:");
    }

    /** PostgreSQL as usual, plus a Redis started with {@code --requirepass}. */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(PostgresTestcontainersConfiguration.class)
    static class PasswordProtectedRedisConfiguration {

        @Bean
        GenericContainer<?> passwordProtectedRedis() {
            return new GenericContainer<>(DockerImageName.parse(TestcontainersConfiguration.REDIS_IMAGE))
                    .withExposedPorts(TestcontainersConfiguration.REDIS_PORT)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);
        }

        @Bean
        DynamicPropertyRegistrar passwordProtectedRedisProperties(GenericContainer<?> passwordProtectedRedis) {
            // The same variables the Compose stack sets; application.yml maps them to spring.data.redis.*.
            return registry -> {
                registry.add("REDIS_HOST", passwordProtectedRedis::getHost);
                registry.add("REDIS_PORT",
                        () -> passwordProtectedRedis.getMappedPort(TestcontainersConfiguration.REDIS_PORT));
                registry.add("REDIS_PASSWORD", () -> REDIS_PASSWORD);
            };
        }
    }
}
