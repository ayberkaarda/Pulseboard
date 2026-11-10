package io.pulseboard.core.infrastructure.security;

import io.pulseboard.core.application.tenancy.TenantContext;
import io.pulseboard.core.domain.tenancy.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request-scoped {@link TenantContext} from the authenticated principal: the {@code tid} claim of a
 * JWT or the tenant of an API key. Runs after authentication and before authorization; unauthenticated
 * requests pass through and are handled by the authorization rules.
 */
public class TenantFilter extends OncePerRequestFilter {

    private final TenantContext tenantContext;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public TenantFilter(TenantContext tenantContext, AuthenticationEntryPoint authenticationEntryPoint) {
        this.tenantContext = tenantContext;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = securityContextHolderStrategy.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        Optional<TenantId> tenantId = resolveTenant(authentication);
        if (tenantId.isEmpty()) {
            securityContextHolderStrategy.clearContext();
            authenticationEntryPoint.commence(request, response,
                    new BadCredentialsException("Credentials are not bound to a tenant"));
            return;
        }
        tenantContext.bind(tenantId.get());
        chain.doFilter(request, response);
    }

    private static Optional<TenantId> resolveTenant(Authentication authentication) {
        if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            return Optional.of(apiKey.getTenantId());
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString(JwtAccessTokenIssuer.TENANT_CLAIM);
            try {
                return claim == null ? Optional.empty() : Optional.of(TenantId.parse(claim));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
