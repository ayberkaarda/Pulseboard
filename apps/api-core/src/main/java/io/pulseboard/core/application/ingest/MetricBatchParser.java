package io.pulseboard.core.application.ingest;

/** Parses a raw ingestion body and validates it against the metric batch JSON Schema. */
public interface MetricBatchParser {

    /**
     * Parses and validates {@code json}.
     *
     * @throws io.pulseboard.core.application.common.InvalidRequestException when the body is not JSON or violates
     *     the schema; the violations name the offending locations as JSON pointers
     */
    MetricBatch parse(byte[] json);
}
