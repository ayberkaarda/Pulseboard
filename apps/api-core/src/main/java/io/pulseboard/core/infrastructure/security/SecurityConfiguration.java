package io.pulseboard.core.infrastructure.security;

import io.pulseboard.core.application.apikey.ApiKeyClientPolicy;
import io.pulseboard.core.application.apikey.ApiKeyService;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.application.streamticket.StreamTicketService;
import io.pulseboard.core.application.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless HTTP security.
 *
 * <ul>
 *   <li>Users authenticate with a JWT bearer access token (roles OWNER, ADMIN, VIEWER).</li>
 *   <li>Machines authenticate with {@code X-Api-Key} (role API_KEY): metric ingestion and read-only catalogue
 *   access. Metric queries are for users (JWT) only.</li>
 *   <li>The tenant of every authenticated request is bound to {@link TenantContext} before authorization.</li>
 *   <li>Anonymous authentication endpoints are throttled per client address by an MVC interceptor bound to the
 *   resolved handler (see {@code api.ratelimit}), so URL encoding variants cannot bypass it.</li>
 *   <li>All security failures are rendered as RFC 9457 problem details.</li>
 *   <li>Actuator endpoints are served on the separate management port only; the matchers below apply there.</li>
 *   <li>{@code GET /api/v1/stream/anomalies} also accepts a one-time {@code ticket} query parameter instead of
 *   the bearer header (see {@link StreamTicketAuthenticationFilter}); no other route looks at that parameter.</li>
 *   <li>{@code /internal/**} has its own chain: only a valid {@code X-Internal-Signature} authenticates there, a
 *   JWT or API key is never looked at. Conversely, the signature header means nothing on any other path.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalApiProperties.class)
public class SecurityConfiguration {

    private static final String OWNER = "OWNER";
    private static final String ADMIN = "ADMIN";
    private static final String VIEWER = "VIEWER";

    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/v3/api-docs.yaml",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/error"
    };

    private static final String[] AUTH_ENDPOINTS = {
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh"
    };

    @Bean
    @Order(1)
    SecurityFilterChain internalSecurityFilterChain(HttpSecurity http, InternalApiProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver)
            throws Exception {
        ProblemDetailsSecurityHandler problemHandler = new ProblemDetailsSecurityHandler(handlerExceptionResolver);
        return http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(InternalSignatureFilter.ROLE))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .addFilterBefore(new InternalSignatureFilter(properties.sharedSecret(), properties.maxBodyBytes(),
                        handlerExceptionResolver), AuthorizationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, ApiKeyService apiKeyService,
            TenantContext tenantContext, RateLimiter rateLimiter, ApiKeyClientPolicy apiKeyClientPolicy,
            StreamTicketService streamTicketService,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver)
            throws Exception {
        ProblemDetailsSecurityHandler problemHandler = new ProblemDetailsSecurityHandler(handlerExceptionResolver);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, AUTH_ENDPOINTS).permitAll()
                        .requestMatchers("/api/v1/api-keys", "/api/v1/api-keys/**").hasAnyRole(OWNER, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/services", "/api/v1/services/**")
                        .hasAnyRole(OWNER, ADMIN, VIEWER, ApiKeyAuthenticationToken.API_KEY_ROLE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/services", "/api/v1/services/**")
                        .hasAnyRole(OWNER, ADMIN)
                        // Not bound to a method: HEAD (routed to GET handlers) and any other verb get the same
                        // role check as the documented method.
                        .requestMatchers("/api/v1/ingest")
                        .hasRole(ApiKeyAuthenticationToken.API_KEY_ROLE)
                        .requestMatchers("/api/v1/metrics", "/api/v1/metrics/**")
                        .hasAnyRole(OWNER, ADMIN, VIEWER)
                        // Acknowledging changes state, so it is narrower than the read access below; not bound
                        // to a method for the same reason as the ingest rule.
                        .requestMatchers("/api/v1/anomalies/*/ack").hasAnyRole(OWNER, ADMIN)
                        // The ticket authority exists only on a request the ticket filter authenticated, and that
                        // filter acts on the stream route alone.
                        .requestMatchers("/api/v1/stream/anomalies")
                        .hasAnyRole(OWNER, ADMIN, VIEWER, StreamTicketAuthenticationFilter.ROLE)
                        .requestMatchers("/api/v1/anomalies", "/api/v1/anomalies/**", "/api/v1/stream/**")
                        .hasAnyRole(OWNER, ADMIN, VIEWER)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                // Credentials are irrelevant on the anonymous auth endpoints, so no API-key lookup runs there.
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService, problemHandler,
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/**"),
                        rateLimiter, apiKeyClientPolicy, handlerExceptionResolver),
                        BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new StreamTicketAuthenticationFilter(streamTicketService, problemHandler,
                        handlerExceptionResolver), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TenantFilter(tenantContext, problemHandler), BearerTokenAuthenticationFilter.class)
                .build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtAccessTokenIssuer.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
