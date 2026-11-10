package io.pulseboard.core.infrastructure.security;

import io.pulseboard.core.application.ingest.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authenticates service to service calls: {@code X-Internal-Signature} must be the lower-case hex HMAC-SHA256 of
 * the raw request body under the shared secret.
 *
 * <p>The header is checked for presence and shape first, then the body is read with a hard limit (a larger body is
 * answered with 413 before anything is hashed), hashed, and compared in constant time. Missing, malformed and wrong
 * signatures are indistinguishable (401). The verified bytes are handed to the controller through a request
 * wrapper, so the body that was authenticated is the body that is processed. The secret and the signatures are
 * never logged.
 */
public class InternalSignatureFilter extends OncePerRequestFilter {

    public static final String SIGNATURE_HEADER = "X-Internal-Signature";
    public static final String ROLE = "INTERNAL";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Pattern SIGNATURE_SHAPE = Pattern.compile("[0-9a-f]{64}");
    private static final String REJECTION = "The internal signature is missing or invalid";

    private final SecretKeySpec key;
    private final int maxBodyBytes;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public InternalSignatureFilter(String sharedSecret, int maxBodyBytes,
            HandlerExceptionResolver handlerExceptionResolver) {
        this.key = new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        this.maxBodyBytes = maxBodyBytes;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> signatures = Collections.list(request.getHeaders(SIGNATURE_HEADER));
        if (signatures.size() != 1 || !SIGNATURE_SHAPE.matcher(signatures.getFirst()).matches()) {
            reject(request, response);
            return;
        }
        if (request.getContentLengthLong() > maxBodyBytes) {
            tooLarge(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            tooLarge(request, response);
            return;
        }
        byte[] expected = HexFormat.of().formatHex(hmac(body)).getBytes(StandardCharsets.US_ASCII);
        byte[] presented = signatures.getFirst().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, presented)) {
            reject(request, response);
            return;
        }
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("analytics-service", null,
                AuthorityUtils.createAuthorityList("ROLE_" + ROLE)));
        securityContextHolderStrategy.setContext(context);
        chain.doFilter(new VerifiedBodyRequest(request, body), response);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) {
        handlerExceptionResolver.resolveException(request, response, null, new BadCredentialsException(REJECTION));
    }

    private void tooLarge(HttpServletRequest request, HttpServletResponse response) {
        handlerExceptionResolver.resolveException(request, response, null, new PayloadTooLargeException(maxBodyBytes));
    }

    /** Serves the already read and verified body again. */
    private static final class VerifiedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private VerifiedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return stream.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return stream.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return stream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new IllegalStateException("Asynchronous reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
