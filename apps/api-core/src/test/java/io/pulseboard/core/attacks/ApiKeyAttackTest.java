package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import java.net.http.HttpResponse;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** API key misuse: revoked keys, mangled keys, keys in the wrong header, and X-Api-Key vs JWT precedence. */
class ApiKeyAttackTest extends AttackTestSupport {

    private static final String SERVICES = "/api/v1/services";

    private TokenResponse owner;
    private TokenResponse otherTenant;
    private ApiKeyCreatedResponse key;

    @BeforeEach
    void setUp() {
        owner = registerTenant();
        otherTenant = registerTenant();
        key = issueApiKey(owner.accessToken(), "collector");
    }

    @Test
    void revokedKeyIsRejectedImmediatelyAndRevocationIsIdempotent() {
        assertThat(withKey(key.key()).statusCode()).isEqualTo(200);

        HttpResponse<String> revoke = raw("DELETE", "/api/v1/api-keys/" + key.id(), null,
                "Authorization", "Bearer " + owner.accessToken());
        assertThat(revoke.statusCode()).isEqualTo(204);

        HttpResponse<String> afterRevoke = withKey(key.key());
        assertThat(afterRevoke.statusCode()).isEqualTo(401);
        assertThat(afterRevoke.body()).contains("Invalid API key");
        assertThat(raw("DELETE", "/api/v1/api-keys/" + key.id(), null,
                "Authorization", "Bearer " + owner.accessToken()).statusCode()).isEqualTo(204);
        assertThat(withKey(key.key()).statusCode()).isEqualTo(401);
    }

    @Test
    void revokedKeyAndUnknownKeyGetTheSameAnswer() {
        raw("DELETE", "/api/v1/api-keys/" + key.id(), null, "Authorization", "Bearer " + owner.accessToken());

        HttpResponse<String> revoked = withKey(key.key());
        HttpResponse<String> unknown = withKey("pb_" + "B".repeat(43));

        assertThat(revoked.statusCode()).isEqualTo(unknown.statusCode());
        assertThat(revoked.body()).isEqualTo(unknown.body());
    }

    @Test
    void mangledKeysAreRejected() {
        String secret = key.key().substring(3);
        assertThat(withKey("").statusCode()).isEqualTo(401);
        assertThat(withKey(secret).statusCode()).isEqualTo(401);
        assertThat(withKey("PB_" + secret).statusCode()).isEqualTo(401);
        assertThat(withKey(key.key().toUpperCase(Locale.ROOT)).statusCode()).isEqualTo(401);
        assertThat(withKey(key.key() + "x").statusCode()).isEqualTo(401);
        assertThat(withKey(key.key().substring(0, key.key().length() - 1)).statusCode()).isEqualTo(401);
        assertThat(withKey(key.key() + "x".repeat(500)).statusCode()).isEqualTo(401);
        assertThat(withKey("pb_' OR '1'='1").statusCode()).isEqualTo(401);
    }

    @Test
    void keyIsNotAcceptedAsBearerToken() {
        assertThat(raw("GET", SERVICES, null, "Authorization", "Bearer " + key.key()).statusCode()).isEqualTo(401);
    }

    @Test
    void keyTogetherWithAnyAuthorizationHeaderIsRejected() {
        // Own JWT + own key, foreign JWT + own key, garbage Authorization + valid key: never a tenant mix-up.
        HttpResponse<String> both = raw("GET", SERVICES, null, API_KEY_HEADER, key.key(),
                "Authorization", "Bearer " + owner.accessToken());
        assertThat(both.statusCode()).isEqualTo(401);
        assertThat(both.body()).contains("not both");
        assertThat(raw("GET", SERVICES, null, API_KEY_HEADER, key.key(),
                "Authorization", "Bearer " + otherTenant.accessToken()).statusCode()).isEqualTo(401);
        assertThat(raw("GET", SERVICES, null, API_KEY_HEADER, key.key(),
                "Authorization", "Basic Zm9vOmJhcg==").statusCode()).isEqualTo(401);
    }

    @Test
    void keyCannotReachAuthenticatedNonCatalogueEndpoints() {
        assertThat(raw("GET", "/actuator/env", null, API_KEY_HEADER, key.key()).statusCode()).isIn(403, 404);
        assertThat(raw("POST", "/api/v1/api-keys", "{\"name\":\"escalate\"}", API_KEY_HEADER, key.key())
                .statusCode()).isEqualTo(403);
    }

    @Test
    void keyWithSurroundingWhitespaceIsAcceptedOnlyAsTheSameKey() {
        assertThat(withKey("  " + key.key() + "  ").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> withKey(String apiKey) {
        return raw("GET", SERVICES, null, API_KEY_HEADER, apiKey);
    }
}
