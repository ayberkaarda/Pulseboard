package io.pulseboard.core.application.streamticket;

import java.time.Duration;
import java.util.Optional;

/** Short-lived storage of stream tickets. Only the SHA-256 digest of a ticket is ever handed to the store. */
public interface StreamTicketStore {

    /**
     * Stores the claims under the digest for the given time.
     *
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the store is unreachable
     */
    void store(String digest, StreamTicketClaims claims, Duration timeToLive);

    /**
     * Returns and removes the claims in one atomic step, so at most one caller ever gets them.
     *
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the store is unreachable
     */
    Optional<StreamTicketClaims> consume(String digest);
}
