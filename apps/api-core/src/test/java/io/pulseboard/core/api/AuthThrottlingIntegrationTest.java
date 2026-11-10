package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Per-account login throttle (production values: 10 attempts per 15 minutes per tenant slug and email). */
class AuthThrottlingIntegrationTest extends IntegrationTest {

    private static final int ACCOUNT_CAPACITY = 10;

    @Test
    void loginAttemptsAreLimitedPerAccountIncludingTheCorrectPassword() {
        String slug = randomSlug();
        register(slug, "owner@example.com");
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < ACCOUNT_CAPACITY; i++) {
            statuses.add(login(slug, "owner@example.com", "wrong-password-" + i).returnResult().getStatus().value());
        }
        assertThat(statuses).containsOnly(401);

        login(slug, "Owner@Example.com", PASSWORD)
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value(HttpHeaders.RETRY_AFTER, value -> assertThat(Long.parseLong(value)).isPositive());
    }

    @Test
    void throttleOfOneAccountDoesNotAffectAnother() {
        String slug = randomSlug();
        register(slug, "owner@example.com");
        for (int i = 0; i <= ACCOUNT_CAPACITY; i++) {
            login(slug, "attacked@example.com", "wrong-password-" + i);
        }
        login(slug, "attacked@example.com", "wrong-password-x").expectStatus().isEqualTo(429);

        login(slug, "owner@example.com", PASSWORD).expectStatus().isOk();
    }

    private RestTestClient.ResponseSpec login(String slug, String email, String password) {
        return client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantSlug", slug, "email", email, "password", password))
                .exchange();
    }
}
