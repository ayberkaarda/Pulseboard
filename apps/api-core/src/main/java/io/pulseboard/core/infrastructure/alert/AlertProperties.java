package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.AlertDispatchPolicy;
import io.pulseboard.core.application.alert.AlertPolicy;
import io.pulseboard.core.application.alert.webhook.WebhookPolicy;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alerting settings bound from {@code pulseboard.alert.*}.
 *
 * @param maxRulesPerTenant rules one tenant may own
 * @param maxRulesPerMetric rules of one metric evaluated per ingestion batch
 * @param maxRulesPerBatch rules evaluated per ingestion batch
 * @param ruleCacheTtl how long enabled rules of a metric are reused between batches
 * @param maxPointAge points older than this never raise a threshold alert
 * @param maxPointFuture points further in the future than this never raise a threshold alert
 * @param eventsPerPeriod alert events one tenant may create per {@code eventsPeriod}
 * @param eventsPeriod period of the event budget
 * @param dispatcher delivery settings
 * @param webhook webhook target and call settings
 */
@ConfigurationProperties("pulseboard.alert")
public record AlertProperties(
        int maxRulesPerTenant,
        int maxRulesPerMetric,
        int maxRulesPerBatch,
        Duration ruleCacheTtl,
        Duration maxPointAge,
        Duration maxPointFuture,
        int eventsPerPeriod,
        Duration eventsPeriod,
        Dispatcher dispatcher,
        Webhook webhook) {

    /**
     * @param enabled whether this instance runs the polling dispatcher
     * @param pollInterval pause between two polls
     * @param batchSize events claimed per poll
     * @param concurrency deliveries running at the same time
     * @param lease how long a claim holds an event; must exceed {@code deliveryTimeout}
     * @param maxAttempts delivery attempts per event, the first included
     * @param backoff wait before attempt 2, 3, ...
     * @param hostFailureThreshold consecutive failures after which deliveries to a host pause
     * @param hostPause length of that pause
     * @param deliveryTimeout hard wall-clock limit for one delivery; also the limit of the webhook call itself
     */
    public record Dispatcher(
            boolean enabled,
            Duration pollInterval,
            int batchSize,
            int concurrency,
            Duration lease,
            int maxAttempts,
            List<Duration> backoff,
            int hostFailureThreshold,
            Duration hostPause,
            Duration deliveryTimeout) {
    }

    /**
     * @param signingSecret HMAC key for the signature header; empty disables the WEBHOOK channel, otherwise at
     *     least 32 characters. Never logged.
     * @param allowHttp accept http targets; tests and local development only
     * @param allowPrivateTargets accept loopback and private addresses; tests and local development only
     * @param allowedPorts ports a target may use
     * @param connectTimeout connect timeout
     * @param readTimeout response timeout
     */
    public record Webhook(
            String signingSecret,
            boolean allowHttp,
            boolean allowPrivateTargets,
            List<Integer> allowedPorts,
            Duration connectTimeout,
            Duration readTimeout) {

        public static final int MIN_SECRET_LENGTH = 32;

        public Webhook {
            signingSecret = signingSecret == null ? "" : signingSecret;
            if (!signingSecret.isEmpty() && signingSecret.length() < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException("pulseboard.alert.webhook.signing-secret "
                        + "(ALERT_WEBHOOK_SIGNING_SECRET) must be empty or at least " + MIN_SECRET_LENGTH
                        + " characters");
            }
            if (allowedPorts == null || allowedPorts.isEmpty()) {
                throw new IllegalArgumentException("pulseboard.alert.webhook.allowed-ports must not be empty");
            }
        }

        /** Hides the secret when the properties are logged or rendered. */
        @Override
        public String toString() {
            return "Webhook[allowHttp=" + allowHttp + ", allowPrivateTargets=" + allowPrivateTargets
                    + ", allowedPorts=" + allowedPorts + "]";
        }
    }

    public AlertPolicy toPolicy() {
        return new AlertPolicy(maxRulesPerTenant, maxRulesPerMetric, maxRulesPerBatch, ruleCacheTtl, maxPointAge,
                maxPointFuture, new RateLimitPolicy(eventsPerPeriod, eventsPeriod));
    }

    public AlertDispatchPolicy toDispatchPolicy() {
        return new AlertDispatchPolicy(dispatcher.batchSize(), dispatcher.lease(), dispatcher.maxAttempts(),
                dispatcher.backoff(), dispatcher.hostFailureThreshold(), dispatcher.hostPause(),
                dispatcher.deliveryTimeout());
    }

    public WebhookPolicy toWebhookPolicy() {
        return new WebhookPolicy(webhook.signingSecret(), webhook.allowHttp(), webhook.allowPrivateTargets(),
                Set.copyOf(webhook.allowedPorts()), webhook.connectTimeout(), webhook.readTimeout(),
                dispatcher.deliveryTimeout());
    }
}
