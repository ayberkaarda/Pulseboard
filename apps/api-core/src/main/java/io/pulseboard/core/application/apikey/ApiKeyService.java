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
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, revokes and authenticates tenant API keys.
 *
 * <p>Key format: {@code pb_} followed by 43 base64url characters (32 random bytes). The first eight characters
 * after {@code pb_} are stored as the visible prefix; the full key is shown exactly once, at issue time.
 */
@Service
public class ApiKeyService {

    /** Literal prefix that makes Pulseboard keys recognisable, for example by secret scanners. */
    public static final String KEY_PREFIX = "pb_";
    private static final int KEY_RANDOM_BYTES = 32;
    private static final int VISIBLE_PREFIX_LENGTH = 8;
    private static final int MAX_PRESENTED_KEY_LENGTH = 128;

    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, Clock clock) {
        this.apiKeyRepository = apiKeyRepository;
        this.clock = clock;
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
     */
    @Transactional
    public void revoke(TenantId tenantId, UUID apiKeyId) {
        if (apiKeyRepository.findByIdAndTenantId(apiKeyId, tenantId).isEmpty()) {
            throw new ResourceNotFoundException("API key", apiKeyId);
        }
        apiKeyRepository.revoke(apiKeyId, tenantId, clock.instant());
    }

    /** Resolves a presented key to its tenant when the key exists and is not revoked. */
    @Transactional
    public Optional<AuthenticatedApiKey> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.length() > MAX_PRESENTED_KEY_LENGTH
                || !presentedKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<ApiKey> apiKey = apiKeyRepository.findByKeyHash(SecureTokens.sha256Hex(presentedKey))
                .filter(ApiKey::isActive);
        if (apiKey.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = apiKey.get();
        if (key.isUsageStaleAt(now)) {
            // Conditional single-column update: it cannot resurrect a key revoked after the read above.
            apiKeyRepository.recordUsage(key.getId(), key.getTenantId(), now, now.minus(ApiKey.LAST_USED_RESOLUTION));
        }
        return Optional.of(new AuthenticatedApiKey(key.getId(), key.getTenantId()));
    }

    /** A freshly issued key together with its one-time plaintext value. */
    public record IssuedApiKey(ApiKey apiKey, String plaintext) {
    }

    /** Identity of a request authenticated with an API key. */
    public record AuthenticatedApiKey(UUID apiKeyId, TenantId tenantId) {
    }
}
