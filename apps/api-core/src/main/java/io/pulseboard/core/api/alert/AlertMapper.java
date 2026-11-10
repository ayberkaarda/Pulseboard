package io.pulseboard.core.api.alert;

import io.pulseboard.core.api.alert.AlertDtos.AlertEventResponse;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleRequest;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleUpdateRequest;
import io.pulseboard.core.application.alert.AlertRuleCommand;
import io.pulseboard.core.domain.alert.AlertEvent;
import io.pulseboard.core.domain.alert.AlertRule;
import io.pulseboard.core.domain.alert.DeliveryStatus;

/** Maps alert rules and events to response bodies and request bodies to commands. */
final class AlertMapper {

    private AlertMapper() {
    }

    static AlertRuleResponse toResponse(AlertRule rule) {
        return new AlertRuleResponse(rule.id(), rule.metricId(), rule.metricName(), rule.metricUnit(),
                rule.serviceName(), rule.name(), rule.condition(), rule.operator(), rule.threshold(),
                rule.minSeverity(), rule.cooldownSeconds(), rule.channel(), rule.target(), rule.enabled(),
                rule.createdBy(), rule.createdAt(), rule.updatedAt(), rule.version());
    }

    static AlertEventResponse toResponse(AlertEvent event) {
        return new AlertEventResponse(event.id(), event.ruleId(), event.ruleName(), event.triggeredAt(),
                event.payload(), event.deliveryStatus(), event.attempts(),
                event.deliveryStatus() == DeliveryStatus.PENDING ? event.nextAttemptAt() : null,
                event.lastError(), event.deliveredAt(), event.sourceRef());
    }

    static AlertRuleCommand toCommand(AlertRuleRequest request) {
        return new AlertRuleCommand(request.metricId(), request.name(), request.condition(), request.operator(),
                request.threshold(), request.minSeverity(), request.cooldownSeconds(), request.channel(),
                request.target(), request.enabled());
    }

    static AlertRuleCommand toCommand(AlertRuleUpdateRequest request) {
        return new AlertRuleCommand(request.metricId(), request.name(), request.condition(), request.operator(),
                request.threshold(), request.minSeverity(), request.cooldownSeconds(), request.channel(),
                request.target(), request.enabled());
    }
}
