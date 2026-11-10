package io.pulseboard.core.api;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.infrastructure.security.JwtProperties;
import io.pulseboard.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Access-token lifetime: tokens signed with the real key are rejected once expired or when exp is missing. */
class JwtValidationIntegrationTest extends IntegrationTest {

    @Autowired
    private JwtProperties jwtProperties;

    private TokenResponse owner;

    @BeforeEach
    void setUpTenant() {
        owner = registerTenant();
    }

    @Test
    void forgedControlTokenWithValidClaimsIsAccepted() {
        Instant now = Instant.now();
        String token = sign(claims(now).expirationTime(Date.from(now.plusSeconds(300))).build());

        get(token).expectStatus().isOk();
    }

    @Test
    void tokenWithoutExpirationIsRejected() {
        String token = sign(claims(Instant.now()).build());

        get(token).expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void expiredTokenIsRejected() {
        Instant issued = Instant.now().minusSeconds(3600);
        String token = sign(claims(issued).expirationTime(Date.from(issued.plusSeconds(900))).build());

        get(token).expectStatus().isUnauthorized();
    }

    private JWTClaimsSet.Builder claims(Instant issuedAt) {
        return new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(owner.userId().toString())
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .claim("tid", owner.tenantId().toString())
                .claim("role", owner.role());
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new MACSigner(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private RestTestClient.ResponseSpec get(String token) {
        return client.get().uri("/api/v1/services").header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange();
    }
}
