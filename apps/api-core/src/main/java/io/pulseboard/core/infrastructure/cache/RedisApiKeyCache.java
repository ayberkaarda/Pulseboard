package io.pulseboard.core.infrastructure.cache;

import io.pulseboard.core.application.apikey.ApiKeyCache;
import io.pulseboard.core.application.apikey.ApiKeyService.AuthenticatedApiKey;
import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@link ApiKeyCache} in Redis. Keys have the form {@code apikey:<sha256 of the key>}; values are
 * {@code A|<api key id>|<tenant id>} for an active key (TTL {@code pulseboard.api-key-cache.ttl}) or
 * {@code R|<token>} for a revocation marker (TTL {@code pulseboard.api-key-cache.revoked-marker-ttl}, much longer
 * than any positive entry or in-flight lookup). Positive entries are written with {@code SET NX}, so they never
 * replace a marker. The random token identifies the revocation that wrote the marker, so cleaning up after a
 * rolled-back revocation is a compare-and-delete that never removes another revocation's marker.
 */
@Component
@EnableConfigurationProperties(ApiKeyCacheProperties.class)
public class RedisApiKeyCache implements ApiKeyCache {

    static final String KEY_PREFIX = "apikey:";
    private static final Logger LOG = LoggerFactory.getLogger(RedisApiKeyCache.class);
    private static final String REVOKED_PREFIX = "R|";
    private static final String ACTIVE_PREFIX = "A|";
    private static final int TOKEN_BYTES = 16;

    /** Deletes the key only when it still holds the given value (atomic compare-and-delete). */
    private static final RedisScript<Long> COMPARE_AND_DELETE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ApiKeyCacheProperties properties;

    public RedisApiKeyCache(StringRedisTemplate redisTemplate, ApiKeyCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public Optional<CachedApiKey> find(String keyHash) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(KEY_PREFIX + keyHash);
        } catch (DataAccessException ex) {
            LOG.warn("API key cache unavailable; authenticating against the database", ex);
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        if (value.startsWith(REVOKED_PREFIX)) {
            return Optional.of(new Revoked());
        }
        return parseActive(value).map(Active::new);
    }

    @Override
    public void putActiveIfAbsent(String keyHash, AuthenticatedApiKey apiKey) {
        String value = ACTIVE_PREFIX + apiKey.apiKeyId() + "|" + apiKey.tenantId();
        try {
            redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + keyHash, value, properties.ttl());
        } catch (DataAccessException ex) {
            LOG.warn("API key cache unavailable; result not cached", ex);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>When the write fails, its outcome is unknown (the command may have been applied before the connection
     * failed). The marker is then removed by token on a best-effort basis; if even that fails, the stray marker
     * only makes lookups of the key consult the database until it expires, it never rejects an active key.
     */
    @Override
    public String markRevoked(String keyHash) {
        String token = SecureTokens.randomUrlSafe(TOKEN_BYTES);
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + keyHash, REVOKED_PREFIX + token,
                    properties.revokedMarkerTtl());
        } catch (DataAccessException ex) {
            removeRevocationMarker(keyHash, token);
            throw new DependencyUnavailableException("API key cache is unavailable; the key was not revoked", ex);
        }
        return token;
    }

    @Override
    public void removeRevocationMarker(String keyHash, String token) {
        try {
            redisTemplate.execute(COMPARE_AND_DELETE, List.of(KEY_PREFIX + keyHash), REVOKED_PREFIX + token);
        } catch (DataAccessException ex) {
            LOG.warn("Could not remove the revocation marker of a rolled back revocation", ex);
        }
    }

    private static Optional<AuthenticatedApiKey> parseActive(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3 || !value.startsWith(ACTIVE_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AuthenticatedApiKey(UUID.fromString(parts[1]), TenantId.parse(parts[2])));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
