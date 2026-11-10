package io.pulseboard.core.infrastructure.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Startup rules of the alert settings: the signing secret, and the switches that weaken request forgery checks. */
class AlertPropertiesTest {

    private static AlertProperties.Webhook webhook(String secret, boolean http, boolean privateTargets) {
        return new AlertProperties.Webhook(secret, http, privateTargets, List.of(443), Duration.ofSeconds(5),
                Duration.ofSeconds(5));
    }

    private static AlertProperties properties(AlertProperties.Webhook webhook) {
        return new AlertProperties(50, 20, 200, Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofMinutes(5), 600,
                Duration.ofHours(1), new AlertProperties.Dispatcher(true, Duration.ofSeconds(5), 10, 4,
                        Duration.ofSeconds(60), 4, List.of(Duration.ofSeconds(10)), 5, Duration.ofSeconds(60),
                        Duration.ofSeconds(20)), webhook);
    }

    @Test
    void aSigningSecretMustBeEmptyOrAtLeast32Characters() {
        assertThatThrownBy(() -> webhook("short-secret", false, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ALERT_WEBHOOK_SIGNING_SECRET").hasMessageNotContaining("short-secret");
        assertThatThrownBy(() -> webhook("x".repeat(31), false, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> webhook("x".repeat(32), false, false)).doesNotThrowAnyException();
        assertThat(webhook(null, false, false).signingSecret()).isEmpty();
        assertThat(properties(webhook("", false, false)).toWebhookPolicy().configured()).isFalse();
        assertThat(properties(webhook("x".repeat(40), false, false)).toWebhookPolicy().configured()).isTrue();
    }

    @Test
    void thePortAllowListMayNotBeEmpty() {
        assertThatThrownBy(() -> new AlertProperties.Webhook("", false, false, List.of(), Duration.ofSeconds(1),
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSecretIsNotPrinted() {
        String secret = "z".repeat(40);

        assertThat(webhook(secret, false, false).toString()).doesNotContain(secret);
        assertThat(properties(webhook(secret, false, false)).toString()).doesNotContain(secret);
        assertThat(properties(webhook(secret, false, false)).toWebhookPolicy().toString()).doesNotContain(secret);
    }

    @Test
    void theRelaxedSwitchesAreOnlyAcceptedWithTheTestOrLocalProfile() {
        for (String profile : List.of("test", "local")) {
            for (AlertProperties.Webhook relaxed : List.of(webhook("", true, false), webhook("", false, true),
                    webhook("", true, true))) {
                MockEnvironment environment = new MockEnvironment();
                environment.setActiveProfiles(profile);
                assertThatCode(() -> new AlertSafetyCheck(properties(relaxed), environment).verify())
                        .as(profile).doesNotThrowAnyException();
            }
        }
        for (String profile : List.of("docker", "prod", "")) {
            MockEnvironment environment = new MockEnvironment();
            if (!profile.isEmpty()) {
                environment.setActiveProfiles(profile);
            }
            for (AlertProperties.Webhook relaxed : List.of(webhook("", true, false), webhook("", false, true))) {
                assertThatThrownBy(() -> new AlertSafetyCheck(properties(relaxed), environment).verify())
                        .as(profile).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("server-side request forgery");
            }
            assertThatCode(() -> new AlertSafetyCheck(properties(webhook("", false, false)), environment).verify())
                    .as(profile).doesNotThrowAnyException();
        }
    }
}
