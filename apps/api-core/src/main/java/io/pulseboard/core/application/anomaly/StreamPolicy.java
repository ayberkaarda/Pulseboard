package io.pulseboard.core.application.anomaly;

import java.time.Duration;

/**
 * Limits of the live anomaly stream.
 *
 * @param maxPerTenant concurrent connections one tenant may hold
 * @param maxTotal concurrent connections of the whole instance
 * @param heartbeatInterval idle time after which a comment line is sent to keep the connection and detect dead
 *     clients
 * @param maxLifetime longest time one connection stays open; the client reconnects (with a fresh token)
 */
public record StreamPolicy(int maxPerTenant, int maxTotal, Duration heartbeatInterval, Duration maxLifetime) {
}
