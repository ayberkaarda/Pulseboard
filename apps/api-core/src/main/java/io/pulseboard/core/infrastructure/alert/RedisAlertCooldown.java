package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.AlertCooldown;
import io.pulseboard.core.application.common.DependencyUnavailableException;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Cooldown keys {@code alert:cooldown:{ruleId}} in Redis, set with {@code SET NX PX} in one atomic command. */
@Component
public class RedisAlertCooldown implements AlertCooldown {

    private static final Logger LOG = LoggerFactory.getLogger(RedisAlertCooldown.class);
    private static final String KEY_PREFIX = "alert:cooldown:";

    private final StringRedisTemplate redis;

    public RedisAlertCooldown(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryStart(UUID ruleId, Duration duration) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + ruleId, "1", duration));
        } catch (RuntimeException ex) {
            throw new DependencyUnavailableException("The cooldown store is unavailable", ex);
        }
    }

    @Override
    public void release(UUID ruleId) {
        try {
            redis.delete(KEY_PREFIX + ruleId);
        } catch (RuntimeException ex) {
            LOG.warn("The cooldown of rule {} could not be released; it runs out by itself", ruleId);
        }
    }
}
