package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import io.pulseboard.core.application.streamticket.StreamTicketPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stream ticket settings bound from {@code pulseboard.stream.ticket.*}.
 *
 * @param ttl how long an unused ticket stays valid
 * @param issueCapacity tickets one user may request per {@code issuePeriod}
 * @param issuePeriod time to refill a user's issuance bucket completely
 */
@ConfigurationProperties("pulseboard.stream.ticket")
public record StreamTicketProperties(Duration ttl, int issueCapacity, Duration issuePeriod) {

    public StreamTicketProperties {
        if (ttl == null || ttl.toMillis() < 1) {
            throw new IllegalArgumentException("pulseboard.stream.ticket.ttl must be a positive duration");
        }
        if (issueCapacity < 1) {
            throw new IllegalArgumentException("pulseboard.stream.ticket.issue-capacity must be at least 1");
        }
        if (issuePeriod == null || issuePeriod.toMillis() < 1) {
            throw new IllegalArgumentException("pulseboard.stream.ticket.issue-period must be a positive duration");
        }
    }

    public StreamTicketPolicy toPolicy() {
        return new StreamTicketPolicy(ttl, new RateLimitPolicy(issueCapacity, issuePeriod));
    }
}
