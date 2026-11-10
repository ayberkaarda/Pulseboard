package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.application.anomaly.AnomalyPublisher;
import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.domain.anomaly.Anomaly;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Publishes anomalies to the Redis channel of their tenant. */
@Component
public class RedisAnomalyPublisher implements AnomalyPublisher {

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public RedisAnomalyPublisher(StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void publish(Anomaly anomaly) {
        String channel = RedisAnomalyChannels.forTenant(anomaly.tenantId());
        try {
            redis.convertAndSend(channel, jsonMapper.writeValueAsString(AnomalyMessage.of(anomaly)));
        } catch (DataAccessException ex) {
            throw new DependencyUnavailableException("The message bus is unavailable; the anomaly was not published",
                    ex);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Anomaly " + anomaly.id() + " cannot be serialised", ex);
        }
    }
}
