package io.pulseboard.core.application.catalog;

import io.pulseboard.core.domain.catalog.Service;
import java.time.Instant;

/**
 * A service together with the time of its newest metric sample.
 *
 * @param lastPointAt newest sample over all metrics of the service; {@code null} when it has none
 */
public record ServiceSummary(Service service, Instant lastPointAt) {
}
