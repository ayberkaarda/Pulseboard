package io.pulseboard.core.application.alert;

import io.pulseboard.core.application.alert.webhook.ResolvedWebhookTarget;
import io.pulseboard.core.application.alert.webhook.UnsafeWebhookTargetException;
import io.pulseboard.core.application.alert.webhook.WebhookPolicy;
import io.pulseboard.core.application.alert.webhook.WebhookSender;
import io.pulseboard.core.application.alert.webhook.WebhookSigner;
import io.pulseboard.core.application.alert.webhook.WebhookTarget;
import io.pulseboard.core.application.alert.webhook.WebhookTargetGuard;
import io.pulseboard.core.application.alert.webhook.WebhookTransportException;
import io.pulseboard.core.domain.alert.DueAlert;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Performs one delivery attempt of a claimed alert event.
 *
 * <p>EMAIL_LOG writes one structured log line. WEBHOOK posts the payload as JSON:
 * <ul>
 *   <li>the target is checked again ({@link WebhookTargetGuard}), the host is resolved once and the connection goes
 *   to that address only;</li>
 *   <li>headers: {@code X-Pulseboard-Signature} (hex HMAC-SHA256 over {@code timestamp + "." + body}, see
 *   {@link WebhookSigner}), {@code X-Pulseboard-Timestamp} (Unix seconds), {@code X-Pulseboard-Event-Id};</li>
 *   <li>2xx is success; 3xx (redirects are never followed) and 4xx except 408 and 429 give up at once; 408, 429,
 *   5xx, network errors, timeouts and DNS failures are retried;</li>
 *   <li>delivery is at least once (a crash or a lost response can repeat it), so receivers deduplicate on the event
 *   id.</li>
 * </ul>
 * Logs name the host and port of a webhook, never the URL, and nothing from the response.
 */
@Service
public class AlertDeliveryService {

    private static final Logger LOG = LoggerFactory.getLogger(AlertDeliveryService.class);
    private static final Logger EMAIL_LOG = LoggerFactory.getLogger("pulseboard.alerts.email");

    private final WebhookTargetGuard guard;
    private final WebhookSender sender;
    private final WebhookPolicy webhookPolicy;
    private final HostCircuitBreaker breaker;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AlertDeliveryService(WebhookTargetGuard guard, WebhookSender sender, WebhookPolicy webhookPolicy,
            HostCircuitBreaker breaker, JsonMapper jsonMapper, Clock clock) {
        this.guard = guard;
        this.sender = sender;
        this.webhookPolicy = webhookPolicy;
        this.breaker = breaker;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public DeliveryOutcome deliver(DueAlert alert) {
        if (!alert.ruleEnabled()) {
            return DeliveryOutcome.failed("rule disabled");
        }
        return switch (alert.channel()) {
            case EMAIL_LOG -> deliverEmailLog(alert);
            case WEBHOOK -> deliverWebhook(alert);
        };
    }

    private DeliveryOutcome deliverEmailLog(DueAlert alert) {
        EMAIL_LOG.info("alert_email event={} rule=\"{}\" tenant={} to=\"{}\" subject=\"{}\" attempt={}",
                alert.eventId(), clean(alert.ruleName()), alert.tenantId(), clean(alert.target()),
                clean(subject(alert)), alert.attempts());
        return DeliveryOutcome.sent();
    }

    private DeliveryOutcome deliverWebhook(DueAlert alert) {
        if (!webhookPolicy.configured()) {
            return DeliveryOutcome.failed("webhook channel is not configured");
        }
        WebhookTarget target;
        try {
            target = guard.parse(alert.target());
        } catch (UnsafeWebhookTargetException ex) {
            return DeliveryOutcome.failed("target rejected: " + ex.getMessage());
        }
        String hostKey = target.hostKey();
        Instant pausedUntil = breaker.pausedUntil(alert.tenantId(), hostKey);
        if (pausedUntil != null) {
            LOG.debug("Delivery postponed, receiver paused: event={} host={}", alert.eventId(), hostKey);
            return DeliveryOutcome.defer(pausedUntil);
        }
        ResolvedWebhookTarget resolved;
        try {
            resolved = guard.resolve(target);
        } catch (UnsafeWebhookTargetException ex) {
            if (ex.isTransientFailure()) {
                breaker.recordFailure(alert.tenantId(), hostKey);
                return DeliveryOutcome.retry(ex.getMessage());
            }
            return DeliveryOutcome.failed("target rejected: " + ex.getMessage());
        }
        byte[] body;
        try {
            body = jsonMapper.writeValueAsBytes(alert.payload());
        } catch (JacksonException ex) {
            return DeliveryOutcome.failed("payload cannot be serialised");
        }
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Pulseboard-Event-Id", alert.eventId().toString());
        headers.put("X-Pulseboard-Timestamp", timestamp);
        headers.put("X-Pulseboard-Signature", WebhookSigner.sign(webhookPolicy.signingSecret(), timestamp, body));
        int status;
        try {
            status = sender.post(resolved, headers, body);
        } catch (WebhookTransportException ex) {
            breaker.recordFailure(alert.tenantId(), hostKey);
            LOG.info("Webhook delivery failed: event={} host={} reason={}", alert.eventId(), hostKey,
                    ex.getMessage());
            return DeliveryOutcome.retry(ex.getMessage());
        }
        if (status >= 200 && status < 300) {
            breaker.recordSuccess(alert.tenantId(), hostKey);
            return DeliveryOutcome.sent();
        }
        LOG.info("Webhook receiver answered with an error: event={} host={} status={}", alert.eventId(), hostKey,
                status);
        if (status == 408 || status >= 500) {
            // The receiver (or the path to it) is unhealthy: counts towards the breaker.
            breaker.recordFailure(alert.tenantId(), hostKey);
            return DeliveryOutcome.retry("HTTP " + status);
        }
        if (status == 429 || status < 200) {
            // The receiver asks to slow down: retried later, but this is not a failure for the breaker.
            return DeliveryOutcome.retry("HTTP " + status);
        }
        // The receiver is alive and refused or redirected the request: retrying the same request cannot help.
        breaker.recordSuccess(alert.tenantId(), hostKey);
        return DeliveryOutcome.failed(status >= 300 && status < 400 ? "HTTP " + status + " (redirects are not followed)"
                : "HTTP " + status);
    }

    private static String subject(DueAlert alert) {
        Object metric = alert.payload().get("metric");
        String metricName = metric instanceof Map<?, ?> map && map.get("name") != null ? map.get("name").toString()
                : "metric";
        return "Alert \"" + alert.ruleName() + "\" on " + metricName;
    }

    /** Keeps values written by users from forging log lines. */
    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}\"]", " ");
    }
}
