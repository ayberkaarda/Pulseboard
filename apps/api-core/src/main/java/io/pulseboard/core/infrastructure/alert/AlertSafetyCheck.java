package io.pulseboard.core.infrastructure.alert;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the webhook safety switches meant for tests and local development are on in a deployment:
 * {@code allow-http} and {@code allow-private-targets} turn off the protection against server-side request forgery,
 * so they are only honoured when the {@code test} or {@code local} profile is active.
 */
@Component
public class AlertSafetyCheck {

    private final AlertProperties properties;
    private final Environment environment;

    public AlertSafetyCheck(AlertProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        boolean relaxed = properties.webhook().allowHttp() || properties.webhook().allowPrivateTargets();
        if (relaxed && !environment.acceptsProfiles(Profiles.of("test", "local"))) {
            throw new IllegalStateException("pulseboard.alert.webhook.allow-http and allow-private-targets weaken "
                    + "the protection against server-side request forgery and are only accepted with the 'test' "
                    + "or 'local' profile");
        }
    }
}
