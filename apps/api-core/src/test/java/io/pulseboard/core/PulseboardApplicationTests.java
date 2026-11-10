package io.pulseboard.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PulseboardApplicationTests {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    void contextLoads() {
        assertThat(restTestClient).isNotNull();
    }

    @Test
    void healthEndpointIsPublicAndUp() {
        restTestClient.get()
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
    void protectedEndpointsRequireAuthentication() {
        restTestClient.get()
                .uri("/api/v1/tenants")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
