package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.common.SecureTokens;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Refresh token reuse, races, expiry and cross-use with access tokens. */
class RefreshTokenAttackTest extends AttackTestSupport {

    private static final int RACERS = 8;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentRefreshWithTheSameTokenIssuesAtMostOneSuccessor() throws Exception {
        TokenResponse initial = registerTenant();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < RACERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return refresh(initial.refreshToken());
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                statuses.add(future.get().statusCode());
            }

            assertThat(statuses).containsOnly(200, 401);
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        }
        // The losing racers presented a rotated token: the whole family, including the winner's successor,
        // is revoked (fail-closed).
        Integer active = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Integer.class,
                initial.userId());
        assertThat(active).isZero();
    }

    @Test
    void stolenRotatedTokenCannotBeUsedAfterLegitimateRotation() {
        TokenResponse initial = registerTenant();
        assertThat(refresh(initial.refreshToken()).statusCode()).isEqualTo(200);

        HttpResponse<String> thief = refresh(initial.refreshToken());

        assertThat(thief.statusCode()).isEqualTo(401);
        assertThat(thief.body()).contains("Invalid refresh token").doesNotContain("accessToken");
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        TokenResponse initial = registerTenant();
        jdbcTemplate.update("UPDATE refresh_token SET issued_at = now() - interval '8 days', "
                + "expires_at = now() - interval '1 day' WHERE token_hash = ?",
                SecureTokens.sha256Hex(initial.refreshToken()));

        assertThat(refresh(initial.refreshToken()).statusCode()).isEqualTo(401);
    }

    @Test
    void accessTokenIsNotAcceptedAsRefreshToken() {
        TokenResponse initial = registerTenant();

        assertThat(refresh(initial.accessToken()).statusCode()).isIn(400, 401);
    }

    @Test
    void refreshTokenResponsesAreNotCacheable() {
        TokenResponse initial = registerTenant();

        HttpResponse<String> response = refresh(initial.refreshToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, "Cache-Control")).contains("no-store");
    }

    @Test
    void oversizedOrEmptyRefreshTokenIsRejectedWithoutLookup() {
        assertThat(refresh("x".repeat(10_000)).statusCode()).isEqualTo(400);
        assertThat(refresh("").statusCode()).isEqualTo(400);
        assertThat(raw("POST", "/api/v1/auth/refresh", "{}").statusCode()).isEqualTo(400);
    }
}
