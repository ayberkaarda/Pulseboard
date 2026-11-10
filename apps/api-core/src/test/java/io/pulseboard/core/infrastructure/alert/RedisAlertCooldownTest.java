package io.pulseboard.core.infrastructure.alert;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.application.common.DependencyUnavailableException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** A cooldown store that cannot be reached is reported as unavailable, and releasing never throws. */
class RedisAlertCooldownTest {

    @Test
    void anUnreachableRedisIsADependencyFailureForStartAndHarmlessForRelease() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        factory.afterPropertiesSet();
        try {
            RedisAlertCooldown cooldown = new RedisAlertCooldown(new StringRedisTemplate(factory));
            UUID rule = UUID.randomUUID();

            assertThatThrownBy(() -> cooldown.tryStart(rule, Duration.ofMinutes(5)))
                    .isInstanceOf(DependencyUnavailableException.class);
            assertThatCode(() -> cooldown.release(rule)).doesNotThrowAnyException();
        } finally {
            factory.destroy();
        }
    }
}
