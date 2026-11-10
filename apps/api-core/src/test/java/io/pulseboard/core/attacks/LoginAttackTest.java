package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** Credential guessing and account/tenant enumeration against the login endpoint. */
class LoginAttackTest extends AttackTestSupport {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final int TIMING_SAMPLES = 9;

    @Value("${pulseboard.rate-limit.auth.account-capacity}")
    private int accountCapacity;

    @Test
    void unknownTenantUnknownUserAndWrongPasswordAreIndistinguishableByResponse() {
        String slug = randomSlug();
        register(slug, OWNER_EMAIL);

        HttpResponse<String> wrongPassword = login(slug, OWNER_EMAIL, "wrong-password-1");
        HttpResponse<String> unknownUser = login(slug, "nobody@example.com", "wrong-password-1");
        HttpResponse<String> unknownTenant = login(randomSlug(), OWNER_EMAIL, "wrong-password-1");

        for (HttpResponse<String> response : List.of(wrongPassword, unknownUser, unknownTenant)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo(wrongPassword.body());
            assertThat(response.headers().map().keySet()).isEqualTo(wrongPassword.headers().map().keySet());
        }
    }

    @Test
    void unknownTenantUnknownUserAndWrongPasswordTakeComparableTime() {
        String slug = randomSlug();
        register(slug, OWNER_EMAIL);
        for (int i = 0; i < 3; i++) {
            login(slug, OWNER_EMAIL, "warm-up-password");
            login(randomSlug(), OWNER_EMAIL, "warm-up-password");
        }
        List<Long> wrongPassword = new ArrayList<>();
        List<Long> unknownUser = new ArrayList<>();
        List<Long> unknownTenant = new ArrayList<>();
        for (int i = 0; i < TIMING_SAMPLES; i++) {
            wrongPassword.add(timeMillis(() -> login(slug, OWNER_EMAIL, "wrong-password-x")));
            unknownUser.add(timeMillis(() -> login(slug, "nobody@example.com", "wrong-password-x")));
            unknownTenant.add(timeMillis(() -> login(randomSlug(), OWNER_EMAIL, "wrong-password-x")));
        }

        long reference = median(wrongPassword);
        // Argon2 dominates every path; a missing dummy hash would make the unknown paths several times faster.
        assertThat(median(unknownUser)).isGreaterThan(reference / 2);
        assertThat(median(unknownTenant)).isGreaterThan(reference / 2);
    }

    @Test
    void emailAndSlugAreCaseInsensitiveButPasswordIsNot() {
        String slug = randomSlug();
        register(slug, OWNER_EMAIL);

        assertThat(login(slug.toUpperCase(Locale.ROOT), "OWNER@EXAMPLE.COM", PASSWORD).statusCode())
                .isEqualTo(200);
        assertThat(login(slug, OWNER_EMAIL, PASSWORD.toUpperCase(Locale.ROOT)).statusCode()).isEqualTo(401);
    }

    @Test
    void oversizedPasswordIsRejectedBeforeHashing() {
        HttpResponse<String> response = login(randomSlug(), OWNER_EMAIL, "p".repeat(100_000));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("ppppp");
    }

    /**
     * Regression for finding login-no-brute-force-protection. The per-account bucket allows
     * {@code account-capacity} login attempts per window, counting every attempt: a successful login uses one,
     * the remaining ones are answered normally (401), and from then on every attempt (also with the correct
     * password) gets an explicit 429 problem with Retry-After.
     */
    @Test
    void repeatedFailedLoginsAreThrottled() {
        String slug = randomSlug();
        register(slug, OWNER_EMAIL);

        assertThat(login(slug, OWNER_EMAIL, PASSWORD).statusCode()).as("first login").isEqualTo(200);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < accountCapacity - 1; i++) {
            statuses.add(login(slug, OWNER_EMAIL, "guess-number-" + i).statusCode());
        }
        assertThat(statuses).as("attempts within the account capacity").hasSize(accountCapacity - 1)
                .containsOnly(401);

        for (String password : List.of("guess-number-over-limit", PASSWORD)) {
            HttpResponse<String> throttled = login(slug, OWNER_EMAIL, password);
            assertThat(throttled.statusCode()).as("attempt over the limit").isEqualTo(429);
            assertThat(header(throttled, "Content-Type")).startsWith(PROBLEM_JSON);
            assertThat(Long.parseLong(header(throttled, "Retry-After"))).isPositive();
            assertThat(throttled.body()).contains("\"status\":429").contains("urn:pulseboard:problem:rate-limited");
        }
    }

    private static long timeMillis(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static long median(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
