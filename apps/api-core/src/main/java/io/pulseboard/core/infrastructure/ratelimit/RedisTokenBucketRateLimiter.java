package io.pulseboard.core.infrastructure.ratelimit;

import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.application.ratelimit.RateLimiterUnavailableException;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@link RateLimiter} backed by a Redis Lua script, so the read-refill-consume cycle is atomic across all
 * api-core instances. Keys have the form {@code rl:<namespace>:<subject>} and expire once the bucket would be
 * full again.
 */
@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "rl:";
    private static final int MAX_SUBJECT_LENGTH = 200;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> script;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = (RedisScript) RedisScript.of(new ClassPathResource("redis/token-bucket.lua"), List.class);
    }

    @Override
    public Decision tryAcquire(String namespace, String subject, RateLimitPolicy policy) {
        return run(namespace, subject, policy, "1");
    }

    @Override
    public Decision check(String namespace, String subject, RateLimitPolicy policy) {
        return run(namespace, subject, policy, "0");
    }

    private Decision run(String namespace, String subject, RateLimitPolicy policy, String requestedTokens) {
        if (namespace == null || namespace.isBlank() || subject == null || subject.isBlank()
                || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("namespace and subject (at most 200 characters) are required");
        }
        List<Long> result;
        try {
            result = redisTemplate.execute(script, List.of(KEY_PREFIX + namespace + ":" + subject),
                    Integer.toString(policy.capacity()), Long.toString(policy.refillPeriod().toMillis()),
                    requestedTokens);
        } catch (DataAccessException ex) {
            throw new RateLimiterUnavailableException("Rate limiter store is unavailable", ex);
        }
        if (result == null || result.size() != 2) {
            throw new RateLimiterUnavailableException("Rate limiter returned an unexpected result", null);
        }
        boolean allowed = result.get(0) == 1L;
        return new Decision(allowed, allowed ? Duration.ZERO : Duration.ofMillis(result.get(1)));
    }
}
