package io.pulseboard.core.application.streamticket;

import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * One-time tickets that let a browser {@code EventSource}, which cannot send an Authorization header, open the
 * live stream. A ticket is 256 random bits; only its SHA-256 digest is stored, so a leaked store cannot be used to
 * open streams. Using a ticket removes it, whatever happens afterwards.
 */
@Service
public class StreamTicketService {

    static final String RATE_LIMIT_NAMESPACE = "stream-ticket";
    private static final int TICKET_BYTES = 32;
    /** 32 bytes in unpadded URL-safe base64. */
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final StreamTicketStore store;
    private final RateLimiter rateLimiter;
    private final StreamTicketPolicy policy;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public StreamTicketService(StreamTicketStore store, RateLimiter rateLimiter, StreamTicketPolicy policy,
            Clock clock) {
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Issues a ticket bound to the tenant and user, valid for the configured time or until the access token
     * expires, whichever is sooner.
     *
     * @throws io.pulseboard.core.application.ratelimit.RateLimitExceededException when the user asks too often
     * @throws io.pulseboard.core.application.ratelimit.RateLimiterUnavailableException when limits cannot be checked
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the store is unreachable
     */
    public IssuedStreamTicket issue(TenantId tenantId, UUID userId, Instant jwtExpiresAt) {
        rateLimiter.acquireOrThrow(RATE_LIMIT_NAMESPACE, userId.toString(), policy.issuance());
        Duration untilExpiry = Duration.between(clock.instant(), jwtExpiresAt);
        Duration timeToLive = untilExpiry.compareTo(policy.timeToLive()) < 0 ? untilExpiry : policy.timeToLive();
        if (timeToLive.toMillis() < 1) {
            timeToLive = Duration.ofMillis(1);
        }
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.store(digest(ticket), new StreamTicketClaims(tenantId, userId, jwtExpiresAt), timeToLive);
        return new IssuedStreamTicket(ticket, (timeToLive.toMillis() + 999) / 1000);
    }

    /**
     * Consumes a presented ticket. Empty for every kind of failure (malformed, unknown, expired, already used,
     * access token expired meanwhile), so callers cannot tell them apart.
     *
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the store is unreachable
     */
    public Optional<StreamTicketClaims> consume(String presented) {
        if (presented == null || !WELL_FORMED.matcher(presented).matches()) {
            return Optional.empty();
        }
        return store.consume(digest(presented))
                .filter(claims -> claims.jwtExpiresAt().isAfter(clock.instant()));
    }

    static String digest(String ticket) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
