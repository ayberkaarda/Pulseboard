package io.pulseboard.core.infrastructure.security;

import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.application.streamticket.StreamTicketClaims;
import io.pulseboard.core.application.streamticket.StreamTicketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authenticates {@code GET /api/v1/stream/anomalies?ticket=...} with a one-time stream ticket. It acts on that
 * route only; a {@code ticket} parameter anywhere else is just an unknown parameter.
 *
 * <p>Exactly one authentication method per request: a ticket together with an Authorization or X-Api-Key header,
 * or a repeated parameter, is refused without touching the ticket. Every failure (malformed, unknown, expired,
 * already used) is the same 401. The ticket is consumed here, before the stream is admitted, so a request refused
 * by a connection cap has still used up its ticket. The resulting authentication carries the tenant, the user and
 * the expiry of the access token that requested the ticket, in the same shape as a bearer token, plus the
 * {@link #ROLE} authority, which is accepted on the stream route only.
 */
public class StreamTicketAuthenticationFilter extends OncePerRequestFilter {

    /** Authority of ticket authenticated requests; the security rules grant it to the stream route only. */
    public static final String ROLE = "STREAM_TICKET";
    public static final String PARAMETER = "ticket";
    public static final String STREAM_PATH = "/api/v1/stream/anomalies";

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final StreamTicketService tickets;
    private final AuthenticationEntryPoint entryPoint;
    private final HandlerExceptionResolver exceptionResolver;
    private final RequestMatcher streamRoute = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, STREAM_PATH);
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public StreamTicketAuthenticationFilter(StreamTicketService tickets, AuthenticationEntryPoint entryPoint,
            HandlerExceptionResolver exceptionResolver) {
        this.tickets = tickets;
        this.entryPoint = entryPoint;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String[]> parameters = request.getParameterMap();
        if (!streamRoute.matches(request) || !parameters.containsKey(PARAMETER)) {
            chain.doFilter(request, response);
            return;
        }
        String[] presented = parameters.get(PARAMETER);
        if (presented.length != 1 || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || request.getHeader(API_KEY_HEADER) != null) {
            reject(request, response);
            return;
        }
        Optional<StreamTicketClaims> claims;
        try {
            claims = tickets.consume(presented[0]);
        } catch (DependencyUnavailableException ex) {
            exceptionResolver.resolveException(request, response, null, ex);
            return;
        }
        if (claims.isEmpty()) {
            reject(request, response);
            return;
        }
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication(claims.get()));
        securityContextHolderStrategy.setContext(context);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        securityContextHolderStrategy.clearContext();
        entryPoint.commence(request, response, new BadCredentialsException(null));
    }

    private static JwtAuthenticationToken authentication(StreamTicketClaims claims) {
        Jwt jwt = Jwt.withTokenValue("stream-ticket")
                .header("alg", "none")
                .subject(claims.userId().toString())
                .claim(JwtAccessTokenIssuer.TENANT_CLAIM, claims.tenantId().toString())
                .issuedAt(Instant.now())
                .expiresAt(claims.jwtExpiresAt())
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
    }
}
