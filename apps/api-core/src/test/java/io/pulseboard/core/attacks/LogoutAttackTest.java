package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.common.SecureTokens;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Logout, the absolute session lifetime and their interplay with rotation and reuse detection. */
@ExtendWith(OutputCaptureExtension.class)
class LogoutAttackTest extends AttackTestSupport {

    private static final String LOGOUT = "/api/v1/auth/logout";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void logoutEndsTheSessionIncludingTokensRotatedBeforeIt() {
        TokenResponse login = registerTenant();
        TokenResponse rotated = tokens(refresh(login.refreshToken()));

        HttpResponse<String> response = logout(rotated.refreshToken());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(header(response, "Cache-Control")).contains("no-store");
        assertThat(refresh(rotated.refreshToken()).statusCode()).isEqualTo(401);
        // The token consumed by the earlier rotation is dead as well: replaying it is reuse, still a plain 401.
        assertThat(refresh(login.refreshToken()).statusCode()).isEqualTo(401);
        assertThat(activeTokens(login.userId())).isZero();
    }

    @Test
    void logoutOfTheFirstTokenAlsoKillsTheSuccessorOfAnEarlierRotation() {
        TokenResponse login = registerTenant();
        TokenResponse rotated = tokens(refresh(login.refreshToken()));

        assertThat(logout(login.refreshToken()).statusCode()).isEqualTo(204);

        assertThat(refresh(rotated.refreshToken()).statusCode()).isEqualTo(401);
    }

    @Test
    void otherSessionsOfTheSameUserSurviveLogout() {
        String slug = randomSlug();
        TokenResponse first = register(slug, "owner@example.com");
        TokenResponse second = tokens(login(slug, "owner@example.com", PASSWORD));

        assertThat(logout(first.refreshToken()).statusCode()).isEqualTo(204);

        assertThat(refresh(first.refreshToken()).statusCode()).isEqualTo(401);
        assertThat(refresh(second.refreshToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void logoutOfOneTenantNeverTouchesAnother() {
        TokenResponse tenantA = registerTenant();
        TokenResponse tenantB = registerTenant();

        assertThat(logout(tenantA.refreshToken()).statusCode()).isEqualTo(204);

        assertThat(activeTokens(tenantB.userId())).isEqualTo(1);
        assertThat(refresh(tenantB.refreshToken()).statusCode()).isEqualTo(200);
        // An identifier of the other tenant is not an accepted credential.
        assertThat(logout(tenantB.tenantId().toString()).statusCode()).isEqualTo(204);
        assertThat(activeTokens(tenantB.userId())).isEqualTo(1);
    }

    @Test
    void logoutIsIdempotentAndUnknownTokensAnswerExactlyLikeKnownOnes() {
        TokenResponse login = registerTenant();

        HttpResponse<String> known = logout(login.refreshToken());
        HttpResponse<String> replayed = logout(login.refreshToken());
        HttpResponse<String> unknown = logout(SecureTokens.randomUrlSafe(32));

        for (HttpResponse<String> response : List.of(replayed, unknown)) {
            assertThat(response.statusCode()).isEqualTo(known.statusCode()).isEqualTo(204);
            assertThat(response.body()).isEqualTo(known.body());
            assertThat(header(response, "Cache-Control")).isEqualTo(header(known, "Cache-Control"));
            assertThat(header(response, "Content-Type")).isEqualTo(header(known, "Content-Type"));
        }
    }

    @Test
    void logoutNeedsNoValidAccessTokenAndIgnoresOnesThatAreSent() {
        TokenResponse login = registerTenant();
        String expired = signWithRealKey(validClaims(login).expirationTime(
                Date.from(Instant.now().minusSeconds(3600))).build());

        HttpResponse<String> withExpired = raw("POST", LOGOUT, body(login.refreshToken()),
                "Authorization", "Bearer " + expired);
        assertThat(withExpired.statusCode()).isEqualTo(204);
        assertThat(refresh(login.refreshToken()).statusCode()).isEqualTo(401);

        TokenResponse another = registerTenant();
        assertThat(raw("POST", LOGOUT, body(another.refreshToken()), "Authorization", "Bearer garbage")
                .statusCode()).isEqualTo(204);
        assertThat(raw("POST", LOGOUT, body(UUID.randomUUID().toString()), "X-Api-Key", "pb_whatever")
                .statusCode()).isEqualTo(204);
    }

    @Test
    void malformedBodiesArePlainBadRequestProblems() {
        String oversized = "{\"refreshToken\":\"" + "x".repeat(10_000) + "\"}";
        for (String body : List.of("{}", "{\"refreshToken\":\"\"}", "{\"refreshToken\":null}", "{not json",
                oversized, "[]")) {
            HttpResponse<String> response = raw("POST", LOGOUT, body);

            assertThat(response.statusCode()).as(body.substring(0, Math.min(20, body.length()))).isEqualTo(400);
            assertThat(header(response, "Content-Type")).startsWith(PROBLEM_JSON);
        }
        assertThat(raw("POST", LOGOUT, null).statusCode()).isEqualTo(400);
    }

    @Test
    void getAndOtherVerbsAreNotLogout() {
        assertThat(raw("GET", LOGOUT, null).statusCode()).isIn(401, 405);
        assertThat(raw("DELETE", LOGOUT, body("x".repeat(40))).statusCode()).isIn(401, 405);
    }

    @Test
    void neitherResponsesNorLogsContainTheTokenOrItsHash(CapturedOutput output) {
        TokenResponse login = registerTenant();
        String hash = SecureTokens.sha256Hex(login.refreshToken());

        HttpResponse<String> response = logout(login.refreshToken());
        logout(login.refreshToken());
        refresh(login.refreshToken());

        assertThat(response.body()).doesNotContain(login.refreshToken()).doesNotContain(hash);
        assertThat(output.getAll()).doesNotContain(login.refreshToken()).doesNotContain(hash);
    }

    @Test
    void sessionsHaveAnAbsoluteLifetimeStoredWithTheFamily() {
        TokenResponse login = registerTenant();

        Instant familyEnd = familyExpiry(login.refreshToken());
        Instant issued = jdbcTemplate.queryForObject("SELECT issued_at FROM refresh_token WHERE token_hash = ?",
                Timestamp.class, SecureTokens.sha256Hex(login.refreshToken())).toInstant();

        assertThat(Duration.between(issued, familyEnd)).isEqualTo(Duration.ofDays(30));
        TokenResponse rotated = tokens(refresh(login.refreshToken()));
        assertThat(familyExpiry(rotated.refreshToken())).isEqualTo(familyEnd);
    }

    @Test
    void rotationNeverExtendsASessionBeyondItsAbsoluteEnd() {
        TokenResponse login = registerTenant();
        Instant end = Instant.now().plus(Duration.ofHours(2));
        shortenFamily(login.refreshToken(), end);

        TokenResponse rotated = tokens(refresh(login.refreshToken()));

        assertThat(rotated.refreshTokenExpiresAt()).isBetween(end.minusMillis(5), end.plusMillis(5));
        assertThat(familyExpiry(rotated.refreshToken())).isEqualTo(familyExpiry(login.refreshToken()));
        TokenResponse again = tokens(refresh(rotated.refreshToken()));
        assertThat(again.refreshTokenExpiresAt()).isBetween(end.minusMillis(5), end.plusMillis(5));
    }

    @Test
    void refreshAfterTheAbsoluteEndFailsAndRevokesTheFamily() {
        TokenResponse login = registerTenant();
        TokenResponse rotated = tokens(refresh(login.refreshToken()));
        shortenFamily(rotated.refreshToken(), Instant.now().minusSeconds(5));

        HttpResponse<String> response = refresh(rotated.refreshToken());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Invalid refresh token").doesNotContain("accessToken");
        assertThat(activeTokens(login.userId())).isZero();
    }

    @Test
    void reuseDetectionStillRevokesTheFamilyAndOtherFamiliesStayUsable() {
        String slug = randomSlug();
        TokenResponse first = register(slug, "owner@example.com");
        TokenResponse other = tokens(login(slug, "owner@example.com", PASSWORD));
        TokenResponse rotated = tokens(refresh(first.refreshToken()));

        assertThat(refresh(first.refreshToken()).statusCode()).isEqualTo(401);

        assertThat(refresh(rotated.refreshToken()).statusCode()).isEqualTo(401);
        assertThat(refresh(other.refreshToken()).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> logout(String refreshToken) {
        return raw("POST", LOGOUT, body(refreshToken));
    }

    private static String body(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    private TokenResponse tokens(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return parse(response.body());
    }

    private TokenResponse parse(String json) {
        return JsonMapper.builder().build().readValue(json, TokenResponse.class);
    }

    private int activeTokens(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId);
    }

    private Instant familyExpiry(String refreshToken) {
        return jdbcTemplate.queryForObject("SELECT family_expires_at FROM refresh_token WHERE token_hash = ?",
                Timestamp.class, SecureTokens.sha256Hex(refreshToken)).toInstant();
    }

    /** Moves the end of the session of {@code refreshToken}; the rows are adjusted so the table checks hold. */
    private void shortenFamily(String refreshToken, Instant end) {
        Timestamp endAt = Timestamp.from(end);
        jdbcTemplate.update("""
                UPDATE refresh_token
                   SET family_expires_at = ?,
                       expires_at = LEAST(expires_at, ?),
                       issued_at = LEAST(issued_at, ?)
                 WHERE family_id = (SELECT family_id FROM refresh_token WHERE token_hash = ?)
                """, endAt, endAt, Timestamp.from(end.minus(Duration.ofDays(1))),
                SecureTokens.sha256Hex(refreshToken));
    }
}
