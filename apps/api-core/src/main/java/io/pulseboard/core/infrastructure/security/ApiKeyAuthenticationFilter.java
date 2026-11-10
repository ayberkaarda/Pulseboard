package io.pulseboard.core.infrastructure.security;

import io.pulseboard.core.application.apikey.ApiKeyService;
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

/**
 * Authenticates requests carrying an {@code X-Api-Key} header by SHA-256 hash lookup. Requests without the
 * header pass through untouched; an invalid or revoked key, or a request that also carries an
 * {@code Authorization} header, is rejected with 401.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";

    private final ApiKeyService apiKeyService;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final RequestMatcher ignoredRequests;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    /**
     * @param ignoredRequests requests on which the header is ignored entirely (no lookup); matched on the decoded
     *     path, so encoded spellings of those paths are ignored as well
     */
    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, AuthenticationEntryPoint authenticationEntryPoint,
            RequestMatcher ignoredRequests) {
        this.apiKeyService = apiKeyService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.ignoredRequests = ignoredRequests;
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
        Optional<ApiKeyService.AuthenticatedApiKey> authenticated = apiKeyService.authenticate(presentedKey.trim());
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
