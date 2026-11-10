package io.pulseboard.core.api.ratelimit;

import io.pulseboard.core.application.auth.AuthThrottlingPolicy;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-client throttle for handlers marked {@link ClientRateLimited}. It runs after handler resolution and before
 * argument resolution, so a rejected request is never parsed, hashed or looked up. Exceptions (429 when the
 * bucket is empty, 503 when the store is unavailable) are rendered by the global problem-details advice.
 *
 * <p>The client is {@link HttpServletRequest#getRemoteAddr()}. Behind the trusted reverse proxy this is the
 * address reported in {@code X-Forwarded-For} (resolved by the container's remote-IP valve, which ignores the
 * header from untrusted peers); it is hashed before being used as a key.
 */
public class ClientRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final AuthThrottlingPolicy policy;

    public ClientRateLimitInterceptor(RateLimiter rateLimiter, AuthThrottlingPolicy policy) {
        this.rateLimiter = rateLimiter;
        this.policy = policy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && isRateLimited(method)) {
            rateLimiter.acquireOrThrow(AuthThrottlingPolicy.CLIENT_NAMESPACE,
                    SecureTokens.sha256Hex(request.getRemoteAddr()), policy.perClient());
        }
        return true;
    }

    private static boolean isRateLimited(HandlerMethod method) {
        return method.hasMethodAnnotation(ClientRateLimited.class)
                || method.getBeanType().isAnnotationPresent(ClientRateLimited.class);
    }
}
