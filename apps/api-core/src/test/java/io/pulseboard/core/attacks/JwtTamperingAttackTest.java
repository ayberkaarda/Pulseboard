package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Forged and tampered access tokens. Every variant must be rejected with 401 problem+json (or 403 when the
 * token is genuine but carries no role), and none may reach another tenant's data.
 */
class JwtTamperingAttackTest extends AttackTestSupport {

    private static final String SERVICES = "/api/v1/services";

    private TokenResponse victim;
    private TokenResponse attacker;

    @BeforeEach
    void setUp() {
        victim = registerTenant();
        attacker = registerTenant();
        createService(victim.accessToken(), "victim-only");
    }

    @Test
    void algNoneIsRejected() {
        String token = encodePart("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                + encodePart(decodePart(attacker.accessToken(), 1)) + ".";

        assertUnauthorized(getWithBearer(SERVICES, token));
    }

    @Test
    void algNoneWithOriginalSignatureIsRejected() {
        String[] parts = attacker.accessToken().split("\\.");
        String token = encodePart("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + parts[1] + "." + parts[2];

        assertUnauthorized(getWithBearer(SERVICES, token));
    }

    @Test
    void tenantClaimSwapWithoutResigningIsRejected() {
        String[] parts = attacker.accessToken().split("\\.");
        String swappedPayload = decodePart(attacker.accessToken(), 1)
                .replace(attacker.tenantId().toString(), victim.tenantId().toString());
        String token = parts[0] + "." + encodePart(swappedPayload) + "." + parts[2];

        HttpResponse<String> response = getWithBearer(SERVICES, token);

        assertUnauthorized(response);
        assertThat(response.body()).doesNotContain("victim-only");
    }

    @Test
    void roleEscalationWithoutResigningIsRejected() {
        String[] parts = attacker.accessToken().split("\\.");
        String payload = decodePart(attacker.accessToken(), 1).replace("\"OWNER\"", "\"API_KEY\"");
        String token = parts[0] + "." + encodePart(payload) + "." + parts[2];

        assertUnauthorized(getWithBearer(SERVICES, token));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = sign("attacker-controlled-secret-0123456789abcdef", JWSAlgorithm.HS256,
                validClaims(attacker).claim("tid", victim.tenantId().toString()).build());

        assertUnauthorized(getWithBearer(SERVICES, token));
    }

    @Test
    void tokenSignedWithRealKeyButHs512IsRejected() {
        String token = sign(jwtProperties.secret(), JWSAlgorithm.HS512, validClaims(attacker).build());

        assertUnauthorized(getWithBearer(SERVICES, token));
    }

    @Test
    void expiredTokenIsRejected() {
        Instant past = Instant.now().minusSeconds(3600);
        String token = signWithRealKey(validClaims(attacker)
                .issueTime(Date.from(past))
                .notBeforeTime(Date.from(past))
                .expirationTime(Date.from(Instant.now().minusSeconds(120)))
                .build());

        assertUnauthorized(getWithBearer(SERVICES, token));
    }

    @Test
    void notYetValidTokenIsRejected() {
        String token = signWithRealKey(validClaims(attacker)
                .notBeforeTime(Date.from(Instant.now().plusSeconds(600)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build());

        assertUnauthorized(getWithBearer(SERVICES, token));
    }

    @Test
    void tokenWithoutTenantClaimIsRejected() {
        assertUnauthorized(getWithBearer(SERVICES, signWithRealKey(validClaims(attacker).claim("tid", null).build())));
        assertUnauthorized(getWithBearer(SERVICES, signWithRealKey(validClaims(attacker).claim("tid", " ").build())));
    }

    @Test
    void tokenWithMalformedTenantClaimIsRejected() {
        HttpResponse<String> response = getWithBearer(SERVICES,
                signWithRealKey(validClaims(attacker).claim("tid", "not-a-uuid").build()));

        assertUnauthorized(response);
        assertThat(response.body()).contains("Credentials are not bound to a tenant");
    }

    @Test
    void tokenWithWrongIssuerOrAudienceIsRejected() {
        assertUnauthorized(getWithBearer(SERVICES,
                signWithRealKey(validClaims(attacker).issuer("someone-else").build())));
        assertUnauthorized(getWithBearer(SERVICES,
                signWithRealKey(validClaims(attacker).audience(List.of("other-api")).build())));
        assertUnauthorized(getWithBearer(SERVICES,
                signWithRealKey(validClaims(attacker).audience((String) null).build())));
    }

    @Test
    void tokenWithoutRoleClaimIsAuthenticatedButGetsNoAccess() {
        HttpResponse<String> response = getWithBearer(SERVICES,
                signWithRealKey(validClaims(attacker).claim("role", null).build()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(header(response, "Content-Type")).startsWith(PROBLEM_JSON);
    }

    @Test
    void garbageAndNonBearerSchemesAreRejected() {
        assertUnauthorized(getWithBearer(SERVICES, "garbage"));
        assertUnauthorized(getWithBearer(SERVICES, attacker.refreshToken()));
        assertUnauthorized(raw("GET", SERVICES, null, "Authorization", "Basic YWRtaW46YWRtaW4="));
        assertUnauthorized(raw("GET", SERVICES, null, "Authorization", "Bearer"));
    }

    @Test
    void rejectionsDoNotLeakTokenInternals() {
        JWTClaimsSet expired = validClaims(attacker)
                .issueTime(Date.from(Instant.now().minusSeconds(3600)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(3600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(120)))
                .build();
        HttpResponse<String> response = getWithBearer(SERVICES, signWithRealKey(expired));

        assertThat(response.body())
                .contains("Authentication is required or the presented credentials are invalid")
                .doesNotContainIgnoringCase("expired")
                .doesNotContainIgnoringCase("nimbus")
                .doesNotContainIgnoringCase("exception");
    }

    private static void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(header(response, "Content-Type")).startsWith(PROBLEM_JSON);
        assertThat(header(response, "WWW-Authenticate")).startsWith("Bearer");
    }
}
