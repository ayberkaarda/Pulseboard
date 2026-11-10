package io.pulseboard.core.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.support.AlertIntegrationTest;
import io.pulseboard.core.support.WebhookReceiver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The scheduled dispatcher on its own (no manual dispatching in these tests), and the per-host circuit breaker: after
 * three consecutive failures deliveries to the host are postponed for three seconds without using up attempts.
 */
@TestPropertySource(properties = {
    "pulseboard.alert.dispatcher.enabled=true",
    "pulseboard.alert.dispatcher.host-failure-threshold=3",
    "pulseboard.alert.dispatcher.host-pause=3s"})
class AlertPollerIntegrationTest extends AlertIntegrationTest {

    @Test
    void thePollerDeliversAFiredEventWithoutAnyHelp() {
        TenantFixture tenant = newTenant();
        String path = hookPath();
        WebhookReceiver.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)));
        AlertRuleResponse rule = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "auto", "GT", 1, WebhookReceiver.url(path)));

        ingestNow(newApiKey(tenant), tenant.metricId(), 10);
        UUID eventId = onlyEvent(rule.id());

        await().atMost(Duration.ofSeconds(20)).until(() -> "SENT".equals(status(eventId)));
        assertThat(requests(path)).hasSize(1);
    }

    @Test
    void aDeadReceiverIsLeftAloneForAWhileSoOthersOnTheSameHostAreNotStarved() {
        TenantFixture tenant = newTenant();
        String dead = hookPath();
        String healthy = hookPath();
        WebhookReceiver.server().stubFor(post(urlEqualTo(dead)).willReturn(aResponse().withStatus(500)));
        WebhookReceiver.server().stubFor(post(urlEqualTo(healthy)).willReturn(aResponse().withStatus(200)));
        AlertRuleResponse deadRule = createRule(tenant.ownerToken(), thresholdBody(tenant.metricId(), "dead", "GT",
                1e99, WebhookReceiver.url(dead)));
        AlertRuleResponse healthyRule = createRule(tenant.ownerToken(), thresholdBody(tenant.metricId(), "healthy",
                "GT", 1e99, WebhookReceiver.url(healthy)));
        List<UUID> deadEvents = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            deadEvents.add(insertEvent(tenant, deadRule.id()));
        }

        // The outcome is stored after the failure was counted, so three stored failures mean the breaker is open.
        await().atMost(Duration.ofSeconds(20)).until(() -> deadEvents.stream()
                .allMatch(id -> "HTTP 500".equals(jdbc.queryForObject(
                        "SELECT last_error FROM alert_event WHERE id = ?", String.class, id))));
        UUID healthyEvent = insertEvent(tenant, healthyRule.id());

        // Within the pause the poller does not even try, and the postponement does not use up an attempt.
        await().during(Duration.ofMillis(1200)).atMost(Duration.ofSeconds(5)).until(() ->
                requests(healthy).isEmpty() && "PENDING".equals(status(healthyEvent)));
        assertThat(jdbc.queryForObject("SELECT attempts FROM alert_event WHERE id = ?", Integer.class, healthyEvent))
                .isZero();
        // After the pause the host is tried again; the healthy receiver answers and the event is delivered.
        await().atMost(Duration.ofSeconds(20)).until(() -> "SENT".equals(status(healthyEvent)));
        assertThat(requests(healthy)).hasSize(1);
        for (UUID id : deadEvents) {
            assertThat(status(id)).isIn("PENDING", "FAILED");
        }
    }
}
