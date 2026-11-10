package io.pulseboard.core.api.anomaly;

import io.pulseboard.core.api.anomaly.AnomalyDtos.AnomalyResponse;
import io.pulseboard.core.api.anomaly.AnomalyDtos.NotificationResponse;
import io.pulseboard.core.application.anomaly.NotificationResult;
import io.pulseboard.core.domain.anomaly.Anomaly;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.UUID;
import org.mapstruct.Mapper;

/** Maps anomalies and notification results to response bodies. */
@Mapper
public interface AnomalyMapper {

    AnomalyResponse toResponse(Anomaly anomaly);

    NotificationResponse toResponse(NotificationResult result);

    default UUID map(TenantId tenantId) {
        return tenantId.value();
    }
}
