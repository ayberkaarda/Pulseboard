package io.pulseboard.core.domain.anomaly;

import java.util.List;

/**
 * One page of anomalies.
 *
 * @param items the anomalies of the page, newest window first
 * @param totalItems number of anomalies that match the filters, across all pages
 */
public record AnomalyPage(List<Anomaly> items, long totalItems) {

    public AnomalyPage {
        items = List.copyOf(items);
    }
}
