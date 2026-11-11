package io.pulseboard.core.api.alert;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.pulseboard.core.domain.alert.AlertChannel;
import io.pulseboard.core.domain.alert.AlertCondition;
import io.pulseboard.core.domain.alert.ComparisonOperator;
import io.pulseboard.core.domain.alert.DeliveryStatus;
import io.pulseboard.core.domain.anomaly.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies of the alert rule and alert event endpoints. */
public final class AlertDtos {

    private AlertDtos() {
    }

    @Schema(name = "AlertRuleRequest", description = "Fields of an alert rule. THRESHOLD rules need `operator` and "
            + "`threshold` and no `minSeverity`; ANOMALY_SEVERITY rules need `minSeverity` and neither of the "
            + "others.")
    public record AlertRuleRequest(
            @Schema(requiredMode = REQUIRED, description = "Metric to watch; it must belong to the caller's tenant.")
            UUID metricId,
            @Schema(requiredMode = REQUIRED, description = "Unique per tenant, 1 to 100 characters.",
                    maxLength = 100) String name,
            @Schema(requiredMode = REQUIRED) AlertCondition condition,
            @Schema(description = "THRESHOLD only: how a stored value is compared with the threshold.")
            ComparisonOperator operator,
            @Schema(description = "THRESHOLD only: finite number.") Double threshold,
            @Schema(description = "ANOMALY_SEVERITY only: the rule fires for this severity and above.")
            Severity minSeverity,
            @Schema(requiredMode = REQUIRED, minimum = "60", maximum = "86400",
                    description = "Seconds after a firing during which the rule stays silent.")
            Integer cooldownSeconds,
            @Schema(requiredMode = REQUIRED) AlertChannel channel,
            @Schema(requiredMode = REQUIRED, maxLength = 2048, description = "WEBHOOK: https URL of the receiver "
                    + "(a DNS name, no credentials, port 443 unless configured otherwise, must not resolve to a "
                    + "private or otherwise internal address). EMAIL_LOG: a recipient label of up to 254 "
                    + "characters.") String target,
            @Schema(description = "Defaults to true.", defaultValue = "true") Boolean enabled) {

        /** Hides the target, which can be a URL with a secret in it. */
        @Override
        public String toString() {
            return "AlertRuleRequest[name=" + name + ", condition=" + condition + ", channel=" + channel + "]";
        }
    }

    @Schema(name = "AlertRuleUpdateRequest", description = "Replaces all fields of an alert rule. `version` is "
            + "the version that was read; when the rule has changed since, the answer is 409.")
    public record AlertRuleUpdateRequest(
            @Schema(requiredMode = REQUIRED, description = "Version of the rule this change is based on.")
            Long version,
            @Schema(requiredMode = REQUIRED) UUID metricId,
            @Schema(requiredMode = REQUIRED, maxLength = 100) String name,
            @Schema(requiredMode = REQUIRED) AlertCondition condition,
            ComparisonOperator operator,
            Double threshold,
            Severity minSeverity,
            @Schema(requiredMode = REQUIRED, minimum = "60", maximum = "86400") Integer cooldownSeconds,
            @Schema(requiredMode = REQUIRED) AlertChannel channel,
            @Schema(requiredMode = REQUIRED, maxLength = 2048) String target,
            @Schema(description = "Defaults to true.", defaultValue = "true") Boolean enabled) {

        /** Hides the target, which can be a URL with a secret in it. */
        @Override
        public String toString() {
            return "AlertRuleUpdateRequest[name=" + name + ", condition=" + condition + "]";
        }
    }

    @Schema(name = "AlertRuleEnabledRequest")
    public record AlertRuleEnabledRequest(
            @Schema(requiredMode = REQUIRED) Boolean enabled,
            @Schema(description = "When given, the change is only applied if the rule still has this version.")
            Long version) {
    }

    @Schema(name = "AlertRule", description = "An alert rule of the caller's tenant.")
    public record AlertRuleResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID metricId,
            @Schema(requiredMode = REQUIRED, description = "Name of the metric.") String metricName,
            @Schema(requiredMode = REQUIRED, description = "Unit of the metric.") String metricUnit,
            @Schema(requiredMode = REQUIRED, description = "Id of the service that owns the metric.")
            UUID serviceId,
            @Schema(requiredMode = REQUIRED, description = "Name of the service that owns the metric.")
            String serviceName,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) AlertCondition condition,
            @Schema(requiredMode = REQUIRED, nullable = true, description = "Null unless THRESHOLD.")
            ComparisonOperator operator,
            @Schema(requiredMode = REQUIRED, nullable = true, description = "Null unless THRESHOLD.")
            Double threshold,
            @Schema(requiredMode = REQUIRED, nullable = true, description = "Null unless ANOMALY_SEVERITY.")
            Severity minSeverity,
            @Schema(requiredMode = REQUIRED) int cooldownSeconds,
            @Schema(requiredMode = REQUIRED) AlertChannel channel,
            @Schema(requiredMode = REQUIRED, description = "Webhook URL or e-mail label. Only visible to "
                    + "authenticated users of the tenant; never written to logs in full. For VIEWER users the "
                    + "target of a WEBHOOK rule is masked to its host (`https://hooks.example.com/***`); OWNER and "
                    + "ADMIN see the full URL.") String target,
            @Schema(requiredMode = REQUIRED) boolean enabled,
            @Schema(requiredMode = REQUIRED, nullable = true, description = "Creating user; null once that user "
                    + "was deleted.") UUID createdBy,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) Instant updatedAt,
            @Schema(requiredMode = REQUIRED, description = "Changes with every update; send it back with a "
                    + "change.") long version,
            @Schema(requiredMode = REQUIRED, nullable = true, description = "Time of the newest alert event of "
                    + "this rule; null if it never fired.") Instant lastTriggeredAt) {

        /** Hides the target, which can be a URL with a secret in it. */
        @Override
        public String toString() {
            return "AlertRuleResponse[id=" + id + ", name=" + name + "]";
        }
    }

    @Schema(name = "AlertEvent", description = "An alert that was raised, with its delivery state. Delivery is "
            + "at least once: a receiver should deduplicate on `id`.")
    public record AlertEventResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID ruleId,
            @Schema(requiredMode = REQUIRED, description = "Current name of the rule.") String ruleName,
            @Schema(requiredMode = REQUIRED) Instant triggeredAt,
            @Schema(requiredMode = REQUIRED, description = "The document that is delivered to the receiver.",
                    additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
            Map<String, Object> payload,
            @Schema(requiredMode = REQUIRED) DeliveryStatus deliveryStatus,
            @Schema(requiredMode = REQUIRED, description = "Delivery attempts started so far.") int attempts,
            @Schema(requiredMode = REQUIRED, nullable = true, description = "When the next attempt is due; null "
                    + "unless PENDING.") Instant nextAttemptAt,
            @Schema(requiredMode = REQUIRED, nullable = true, description = "Reason of the last failed attempt "
                    + "(a status code or an error class, never a URL or a response body).") String lastError,
            @Schema(requiredMode = REQUIRED, nullable = true, description = "Null unless SENT.")
            Instant deliveredAt,
            @Schema(requiredMode = REQUIRED, description = "What caused the event: the timestamp of the metric "
                    + "point or the id of the anomaly. Unique per rule.") String sourceRef) {
    }

    @Schema(name = "AlertEventPage", description = "One page of alert events, newest first.")
    public record AlertEventPageResponse(
            @Schema(requiredMode = REQUIRED) List<AlertEventResponse> items,
            @Schema(requiredMode = REQUIRED, description = "Zero-based page number.") int page,
            @Schema(requiredMode = REQUIRED, description = "Requested page size.") int size,
            @Schema(requiredMode = REQUIRED, description = "Number of events that match the filters.")
            long totalItems,
            @Schema(requiredMode = REQUIRED, description = "Number of pages of this size.") long totalPages) {
    }
}
