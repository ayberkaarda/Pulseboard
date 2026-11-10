package io.pulseboard.core.api.metrics;

import io.pulseboard.core.api.metrics.MetricPointsDtos.MetricPointBucket;
import io.pulseboard.core.api.metrics.MetricPointsDtos.MetricPointsResponse;
import io.pulseboard.core.application.query.MetricSeries;
import io.pulseboard.core.domain.metrics.SampleBucket;
import java.time.Duration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Maps downsampled series to response bodies. */
@Mapper
public interface MetricPointsMapper {

    @Mapping(target = "points", source = "buckets")
    MetricPointsResponse toResponse(MetricSeries series);

    @Mapping(target = "ts", source = "start")
    @Mapping(target = "count", source = "sampleCount")
    MetricPointBucket toResponse(SampleBucket bucket);

    default String toIsoDuration(Duration duration) {
        return duration.toString();
    }
}
