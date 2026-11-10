package io.pulseboard.core.attacks;

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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Black-box attack helpers: a raw HTTP client that sends paths and headers exactly as given (no URI
 * normalisation), and a JWT forger that can sign with the real test key or any other key.
 */
abstract class AttackTestSupport extends IntegrationTest {

    protected static final String JSON = "application/json";
    protected static final String PROBLEM_JSON = "application/problem+json";

    @Value("${local.server.port}")
    private int port;

    @Value("${local.management.port}")
    private int managementPort;

    @Autowired
    protected JwtProperties jwtProperties;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Sends a request with the path taken verbatim; {@code headers} are name/value pairs. */
    protected HttpResponse<String> raw(String method, String path, String body, String... headers) {
        return send(port, method, path, body, headers);
    }

    /** Like {@link #raw}, but against the separate management (actuator) port. */
    protected HttpResponse<String> rawOnManagementPort(String method, String path) {
        return send(managementPort, method, path, null);
    }

    private HttpResponse<String> send(int targetPort, String method, String path, String body, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .timeout(Duration.ofSeconds(30));
        boolean contentTypeSet = false;
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
            contentTypeSet |= "Content-Type".equalsIgnoreCase(headers[i]);
        }
        if (body != null && !contentTypeSet) {
            builder.header("Content-Type", JSON);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("Request failed: " + method + " " + path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: " + method + " " + path, ex);
        }
    }

    protected HttpResponse<String> getWithBearer(String path, String token) {
        return raw("GET", path, null, "Authorization", "Bearer " + token);
    }

    protected static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    protected String loginBody(String slug, String email, String password) {
        return "{\"tenantSlug\":\"" + slug + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    protected HttpResponse<String> login(String slug, String email, String password) {
        return raw("POST", "/api/v1/auth/login", loginBody(slug, email, password));
    }

    protected HttpResponse<String> refresh(String refreshToken) {
        return raw("POST", "/api/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    /** Claims identical in shape to the ones the application issues, valid for five minutes. */
    protected JWTClaimsSet.Builder validClaims(TokenResponse tokens) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(tokens.userId().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tid", tokens.tenantId().toString())
                .claim("role", tokens.role());
    }

    protected String signWithRealKey(JWTClaimsSet claims) {
        return sign(jwtProperties.secret(), JWSAlgorithm.HS256, claims);
    }

    protected static String sign(String secret, JWSAlgorithm algorithm, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    protected static String decodePart(String token, int index) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[index]), StandardCharsets.UTF_8);
    }

    protected static String encodePart(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
