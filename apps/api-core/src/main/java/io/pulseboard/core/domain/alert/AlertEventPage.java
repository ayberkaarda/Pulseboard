package io.pulseboard.core.domain.alert;

import java.util.List;

/**
 * One page of alert events.
 *
 * @param items the events of the page, newest first
 * @param totalItems number of events that match the filters, across all pages
 */
public record AlertEventPage(List<AlertEvent> items, long totalItems) {

    public AlertEventPage {
        items = List.copyOf(items);
    }
}
