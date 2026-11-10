package io.pulseboard.core.application.apikey;

import io.pulseboard.core.application.apikey.ApiKeyService.AuthenticatedApiKey;
import java.util.Optional;

/**
 * Short-lived cache of API key authentication results, keyed by the SHA-256 hash of the key.
 *
 * <p>Revocation must take effect immediately even though positive results are cached. A revocation therefore
 * replaces the entry with a revocation marker that outlives any positive entry, and positive entries are only
 * ever written when no entry exists. A lookup that read the key from the database before the revocation
 * committed can thus not re-populate the cache afterwards.
 *
 * <p>A marker only blocks positive caching; it is not trusted as the revocation itself. A key that hits a marker
 * is checked against the database, which stays the single source of truth. A marker left behind by a revocation
 * that rolled back (or whose cache write had an unknown outcome) therefore never locks out an active key.
 */
public interface ApiKeyCache {

    /**
     * The cached state of a key, or empty when nothing is cached or the cache cannot be reached (callers fall
     * back to the database).
     */
    Optional<CachedApiKey> find(String keyHash);

    /** Caches an active key unless an entry (active or revoked) already exists. Failures are ignored. */
    void putActiveIfAbsent(String keyHash, AuthenticatedApiKey apiKey);

    /**
     * Marks the key as revoked, replacing any cached positive result.
     *
     * @return a token identifying this marker, for {@link #removeRevocationMarker}
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the cache cannot be
     *     reached; the revocation must then fail, or a cached positive result could outlive it
     */
    String markRevoked(String keyHash);

    /**
     * Removes the marker written with {@code token}, and only that marker: a marker written meanwhile by another
     * revocation is kept. Used when the revoking transaction rolls back. Failures are ignored.
     */
    void removeRevocationMarker(String keyHash, String token);

    /** A cached authentication result. */
    sealed interface CachedApiKey permits Active, Revoked {
    }

    /** The key was active when it was cached. */
    record Active(AuthenticatedApiKey apiKey) implements CachedApiKey {
    }

    /** A revocation marker: positive caching is blocked and the key is checked against the database. */
    record Revoked() implements CachedApiKey {
    }
}
