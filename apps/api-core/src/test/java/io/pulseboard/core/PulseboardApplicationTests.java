package io.pulseboard.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

class PulseboardApplicationTests extends IntegrationTest {

    @Value("${local.management.port}")
    private int managementPort;

    private RestTestClient management;

    @BeforeEach
    void managementClient() {
        management = RestTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
    }

    @Test
    void contextLoads() {
        assertThat(client).isNotNull();
    }

    @Test
    void healthEndpointIsPublicAndUpOnTheManagementPort() {
        management.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("\"status\":\"UP\"")
                        .doesNotContain("\"components\"")
                        .doesNotContain("\"details\""));
    }

    @Test
    void prometheusIsServedOnlyOnTheManagementPort() {
        management.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("jvm_"));
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
        client.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
    }

    @Test
    void protectedEndpointsRequireAuthenticationAndAnswerWithProblemDetails() {
        client.get()
                .uri("/api/v1/tenants")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized");
    }
}
