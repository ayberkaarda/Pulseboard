package io.pulseboard.core.application.apikey;

import io.pulseboard.core.application.common.ResourceNotFoundException;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.domain.identity.ApiKey;
import io.pulseboard.core.domain.identity.ApiKeyRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues, revokes and authenticates tenant API keys.
 *
 * <p>Key format: {@code pb_} followed by 43 base64url characters (32 random bytes). The first eight characters
 * after {@code pb_} are stored as the visible prefix; the full key is shown exactly once, at issue time.
 *
 * <p>Authentication results are cached (see {@link ApiKeyCache}), so a busy ingestion agent does not cost a
 * database lookup per batch. Revocation through {@link #revoke} takes effect immediately: it writes a revocation
 * marker into the cache inside the revoking transaction and fails when the cache cannot be reached. Because
 * cache hits skip the database, {@code last_used_at} is refreshed only when the cache entry has expired.
 */
@Service
public class ApiKeyService {

    /** Literal prefix that makes Pulseboard keys recognisable, for example by secret scanners. */
    public static final String KEY_PREFIX = "pb_";
    private static final int KEY_RANDOM_BYTES = 32;
    private static final int VISIBLE_PREFIX_LENGTH = 8;
    private static final int MAX_PRESENTED_KEY_LENGTH = 128;

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyCache apiKeyCache;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ApiKeyCache apiKeyCache, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyCache = apiKeyCache;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public IssuedApiKey issue(TenantId tenantId, String name) {
        String secret = SecureTokens.randomUrlSafe(KEY_RANDOM_BYTES);
        String plaintext = KEY_PREFIX + secret;
        ApiKey apiKey = apiKeyRepository.save(new ApiKey(tenantId, name.trim(), SecureTokens.sha256Hex(plaintext),
                secret.substring(0, VISIBLE_PREFIX_LENGTH), clock.instant()));
        return new IssuedApiKey(apiKey, plaintext);
    }

    /**
     * Revokes a key of the tenant with a conditional update of {@code revoked_at} only. Idempotent; a key of
     * another tenant is reported as not found.
     *
     * <p>The cache marker is written before the transaction commits, so no request can be authenticated from a
     * cached result once the revocation is visible. When the cache cannot be reached the revocation is rolled back
     * (503), and when the transaction rolls back for any other reason this revocation's marker (identified by its
     * token) is removed again; a marker written meanwhile by a concurrent revocation that committed is kept. A call
     * that revokes nothing because the key was already revoked writes no marker at all.
     */
    @Transactional
    public void revoke(TenantId tenantId, UUID apiKeyId) {
        ApiKey apiKey = apiKeyRepository.findByIdAndTenantId(apiKeyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("API key", apiKeyId));
        int revoked = apiKeyRepository.revoke(apiKeyId, tenantId, clock.instant());
        if (revoked == 0) {
            // Already revoked by a committed transaction (the conditional update waits for any open one): its
            // marker stays, and this call must not replace it, or its rollback would remove that protection.
            return;
        }
        String keyHash = apiKey.getKeyHash();
        String markerToken = apiKeyCache.markRevoked(keyHash);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        apiKeyCache.removeRevocationMarker(keyHash, markerToken);
                    }
                }
            });
        }
    }

    /** Resolves a presented key to its tenant when the key exists and is not revoked. */
    public Optional<AuthenticatedApiKey> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.length() > MAX_PRESENTED_KEY_LENGTH
                || !presentedKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        String keyHash = SecureTokens.sha256Hex(presentedKey);
        Optional<ApiKeyCache.CachedApiKey> cached = apiKeyCache.find(keyHash);
        if (cached.isPresent() && cached.get() instanceof ApiKeyCache.Active active) {
            return Optional.of(active.apiKey());
        }
        // A revocation marker only blocks positive caching; the database decides. This keeps a marker that
        // outlived a rolled-back revocation from locking out an active key.
        boolean markerPresent = cached.isPresent();
        DatabaseLookup lookup = writeTransaction.execute(status -> lookUpAndRecordUsage(keyHash));
        if (lookup == null || lookup.apiKey() == null) {
            return Optional.empty();
        }
        if (lookup.cacheable() && !markerPresent) {
            apiKeyCache.putActiveIfAbsent(keyHash, lookup.apiKey());
        }
        return Optional.of(lookup.apiKey());
    }

    private DatabaseLookup lookUpAndRecordUsage(String keyHash) {
        Optional<ApiKey> apiKey = apiKeyRepository.findByKeyHash(keyHash).filter(ApiKey::isActive);
        if (apiKey.isEmpty()) {
            return new DatabaseLookup(null, false);
        }
        ApiKey key = apiKey.get();
        AuthenticatedApiKey authenticated = new AuthenticatedApiKey(key.getId(), key.getTenantId());
        Instant now = clock.instant();
        if (!key.isUsageStaleAt(now)) {
            return new DatabaseLookup(authenticated, true);
        }
        // Conditional single-column update: it cannot resurrect a key revoked after the read above. When it
        // updates nothing, the key may have been revoked concurrently, so the result is not cached.
        int updated = apiKeyRepository.recordUsage(key.getId(), key.getTenantId(), now,
                now.minus(ApiKey.LAST_USED_RESOLUTION));
        return new DatabaseLookup(authenticated, updated == 1);
    }

    /** Result of the database path: the active key (or {@code null}) and whether it may be cached. */
    private record DatabaseLookup(AuthenticatedApiKey apiKey, boolean cacheable) {
    }

    /** A freshly issued key together with its one-time plaintext value. */
    public record IssuedApiKey(ApiKey apiKey, String plaintext) {
    }

    /** Identity of a request authenticated with an API key. */
    public record AuthenticatedApiKey(UUID apiKeyId, TenantId tenantId) {
    }
}
