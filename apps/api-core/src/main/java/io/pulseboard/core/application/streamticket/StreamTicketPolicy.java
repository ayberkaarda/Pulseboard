package io.pulseboard.core.application.streamticket;

import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.time.Duration;

/**
 * Settings of stream tickets.
 *
 * @param timeToLive how long an unused ticket stays valid
 * @param issuance how often one user may request tickets
 */
public record StreamTicketPolicy(Duration timeToLive, RateLimitPolicy issuance) {
}
