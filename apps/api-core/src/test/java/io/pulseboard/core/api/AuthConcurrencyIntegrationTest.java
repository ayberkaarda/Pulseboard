package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.support.IntegrationTest;
import io.pulseboard.core.support.LockBarrier;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.ExchangeResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression for refresh-family-revoke-misses-concurrent-successor: a family revocation triggered by token reuse
 * must also revoke a successor that a concurrent rotation is committing at the same time.
 */
class AuthConcurrencyIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Test
    void reuseDuringConcurrentRotationRevokesTheNewSuccessor() throws Exception {
        TokenResponse initial = registerTenant();
        String stolen = initial.refreshToken();
        String current = refresh(stolen).refreshToken();
        UUID familyId = jdbcTemplate.queryForObject("SELECT family_id FROM refresh_token WHERE token_hash = ?",
                UUID.class, SecureTokens.sha256Hex(current));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CompletableFuture<Outcome> rotation;
        CompletableFuture<Outcome> reuse;
        try (LockBarrier barrier = LockBarrier.lockRow(jdbcUrl, "app_user", initial.userId())) {
            // The rotation of the current token stops on the user row: before the fix at the foreign-key check of
            // the successor insert (after locking the current token), after the fix on the serialization lock.
            rotation = CompletableFuture.supplyAsync(() -> refresh(current), executor);
            barrier.awaitWaitingSessions(1);
            // Reuse of the already rotated token starts while that rotation is still open.
            reuse = CompletableFuture.supplyAsync(() -> refresh(stolen), executor);
            barrier.awaitWaitingSessions(2);
            barrier.release();
        }
        Outcome rotationResult = rotation.get(30, TimeUnit.SECONDS);
        Outcome reuseResult = reuse.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(reuseResult.status()).isEqualTo(401);
        Integer activeInFamily = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE family_id = ? AND revoked_at IS NULL", Integer.class,
                familyId);
        assertThat(activeInFamily).as("active tokens left in the revoked family").isZero();
        if (rotationResult.status() == 200) {
            assertThat(refresh(rotationResult.refreshToken()).status()).isEqualTo(401);
        } else {
            assertThat(rotationResult.status()).isEqualTo(401);
        }
    }

    private Outcome refresh(String refreshToken) {
        ExchangeResult result = client.post().uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .exchange()
                .returnResult();
        int status = result.getStatus().value();
        String successor = status == 200
                ? jsonMapper.readValue(result.getResponseBodyContent(), TokenResponse.class).refreshToken()
                : null;
        return new Outcome(status, successor);
    }

    /** HTTP status of a refresh call and, on success, the issued refresh token. */
    private record Outcome(int status, String refreshToken) {
    }
}
