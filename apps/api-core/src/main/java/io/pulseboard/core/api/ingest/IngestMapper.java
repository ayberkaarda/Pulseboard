package io.pulseboard.core.api.ingest;

import io.pulseboard.core.api.ingest.IngestDtos.IngestResponse;
import io.pulseboard.core.api.ingest.IngestDtos.RejectedPoint;
import io.pulseboard.core.application.ingest.IngestionResult;
import org.mapstruct.Mapper;

/** Maps ingestion results to response bodies. */
@Mapper
public interface IngestMapper {

    IngestResponse toResponse(IngestionResult result);

    RejectedPoint toResponse(IngestionResult.Rejection rejection);
}
