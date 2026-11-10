package io.pulseboard.core.infrastructure.security;

import io.pulseboard.core.application.apikey.ApiKeyClientPolicy;
import io.pulseboard.core.application.apikey.ApiKeyService;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.application.ratelimit.RateLimitExceededException;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.application.ratelimit.RateLimiterUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authenticates requests carrying an {@code X-Api-Key} header by SHA-256 hash lookup. Requests without the
 * header pass through untouched; an invalid or revoked key, or a request that also carries an
 * {@code Authorization} header, is rejected with 401.
 *
 * <p>Before any cache or database lookup, two per-client buckets are applied (the client is the remote address,
 * resolved through trusted proxies only, and hashed):
 * <ol>
 *   <li>{@code apikey-client}: every request with the header takes a token;</li>
 *   <li>{@code apikey-invalid}: a token is reserved before the lookup and given back when the key turns out to
 *   be valid, so failures are counted atomically even for parallel requests. Once it is empty,
 *   every key from that client is rejected with 429 without a lookup, so guessing keys or replaying a revoked
 *   key cannot drive unbounded Redis and database work.</li>
 * </ol>
 * Throttled requests get 429 with {@code Retry-After}; an unreachable rate-limit store fails closed (503).
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";

    private final ApiKeyService apiKeyService;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final RequestMatcher ignoredRequests;
    private final RateLimiter rateLimiter;
    private final ApiKeyClientPolicy clientPolicy;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    /**
     * @param ignoredRequests requests on which the header is ignored entirely (no lookup); matched on the decoded
     *     path, so encoded spellings of those paths are ignored as well
     */
    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, AuthenticationEntryPoint authenticationEntryPoint,
            RequestMatcher ignoredRequests, RateLimiter rateLimiter, ApiKeyClientPolicy clientPolicy,
            HandlerExceptionResolver handlerExceptionResolver) {
        this.apiKeyService = apiKeyService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.ignoredRequests = ignoredRequests;
        this.rateLimiter = rateLimiter;
        this.clientPolicy = clientPolicy;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ignoredRequests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presentedKey = request.getHeader(API_KEY_HEADER);
        if (presentedKey == null) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            reject(request, response, "Send either an Authorization header or an X-Api-Key header, not both");
            return;
        }
        String client = SecureTokens.sha256Hex(request.getRemoteAddr());
        Optional<ApiKeyService.AuthenticatedApiKey> authenticated;
        try {
            rateLimiter.acquireOrThrow(ApiKeyClientPolicy.CLIENT_NAMESPACE, client, clientPolicy.perClient());
            // The token for a possible failure is reserved before the lookup, so parallel lookups cannot all pass
            // the check before the first failure is counted; a successful authentication gives it back.
            rateLimiter.acquireOrThrow(ApiKeyClientPolicy.INVALID_NAMESPACE, client, clientPolicy.invalidPerClient());
            authenticated = apiKeyService.authenticate(presentedKey.trim());
            if (authenticated.isPresent()) {
                rateLimiter.refund(ApiKeyClientPolicy.INVALID_NAMESPACE, client, clientPolicy.invalidPerClient());
            }
        } catch (RateLimitExceededException | RateLimiterUnavailableException ex) {
            securityContextHolderStrategy.clearContext();
            handlerExceptionResolver.resolveException(request, response, null, ex);
            return;
        }
        if (authenticated.isEmpty()) {
            reject(request, response, "Invalid API key");
            return;
        }
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(new ApiKeyAuthenticationToken(authenticated.get().apiKeyId(),
                authenticated.get().tenantId()));
        securityContextHolderStrategy.setContext(context);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException, ServletException {
        securityContextHolderStrategy.clearContext();
        authenticationEntryPoint.commence(request, response, new BadCredentialsException(message));
    }
}
