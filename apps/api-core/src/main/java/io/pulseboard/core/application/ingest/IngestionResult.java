package io.pulseboard.core.application.ingest;

import java.util.List;

/**
 * Outcome of an ingestion batch.
 *
 * @param accepted number of points stored
 * @param rejected points that were not stored, in ascending index order
 */
public record IngestionResult(int accepted, List<Rejection> rejected) {

    public IngestionResult {
        rejected = List.copyOf(rejected);
    }

    /**
     * A point that was not stored.
     *
     * @param index zero-based position of the point in the batch
     * @param reason why it was not stored
     */
    public record Rejection(int index, String reason) {
    }
}
