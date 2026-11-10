package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.application.streamticket.StreamTicketClaims;
import io.pulseboard.core.application.streamticket.StreamTicketStore;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tickets in Redis under {@code stream:ticket:{sha256}} with a TTL. Consuming uses {@code GETDEL}, one atomic
 * command, so concurrent presentations of the same ticket cannot both succeed.
 */
@Component
public class RedisStreamTicketStore implements StreamTicketStore {

    static final String KEY_PREFIX = "stream:ticket:";
    private static final Logger LOG = LoggerFactory.getLogger(RedisStreamTicketStore.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public RedisStreamTicketStore(StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void store(String digest, StreamTicketClaims claims, Duration timeToLive) {
        String value = jsonMapper.writeValueAsString(new Stored(claims.tenantId().toString(),
                claims.userId().toString(), claims.jwtExpiresAt().getEpochSecond()));
        try {
            redis.opsForValue().set(KEY_PREFIX + digest, value, timeToLive);
        } catch (DataAccessException ex) {
            throw new DependencyUnavailableException("Stream ticket store is unavailable", ex);
        }
    }

    @Override
    public Optional<StreamTicketClaims> consume(String digest) {
        String value;
        try {
            value = redis.opsForValue().getAndDelete(KEY_PREFIX + digest);
        } catch (DataAccessException ex) {
            throw new DependencyUnavailableException("Stream ticket store is unavailable", ex);
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            Stored stored = jsonMapper.readValue(value, Stored.class);
            return Optional.of(new StreamTicketClaims(TenantId.parse(stored.tenantId()),
                    UUID.fromString(stored.userId()), Instant.ofEpochSecond(stored.jwtExp())));
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Discarded an unreadable stream ticket record");
            return Optional.empty();
        }
    }

    private record Stored(String tenantId, String userId, long jwtExp) {
    }
}
