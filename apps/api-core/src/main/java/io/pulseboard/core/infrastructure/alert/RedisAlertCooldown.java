package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.AlertCooldown;
import io.pulseboard.core.application.common.DependencyUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Cooldown keys {@code alert:cooldown:{ruleId}} in Redis, set with {@code SET NX PX} in one atomic command. The
 * value is a random token of the caller, and a release deletes the key only while it still holds that token
 * (compare-and-delete in Lua), so a caller whose period already ran out cannot end the period of a later one.
 */
@Component
public class RedisAlertCooldown implements AlertCooldown {

    private static final Logger LOG = LoggerFactory.getLogger(RedisAlertCooldown.class);
    private static final String KEY_PREFIX = "alert:cooldown:";
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisAlertCooldown(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> tryStart(UUID ruleId, Duration duration) {
        String token = UUID.randomUUID().toString();
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + ruleId, token, duration))
                    ? Optional.of(token) : Optional.empty();
        } catch (RuntimeException ex) {
            throw new DependencyUnavailableException("The cooldown store is unavailable", ex);
        }
    }

    @Override
    public void release(UUID ruleId, String token) {
        try {
            redis.execute(RELEASE, List.of(KEY_PREFIX + ruleId), token);
        } catch (RuntimeException ex) {
            LOG.warn("The cooldown of rule {} could not be released; it runs out by itself", ruleId);
        }
    }
}
