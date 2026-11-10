package io.pulseboard.core.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The internal shared secret is mandatory: the application must not start without a long enough one. */
class InternalApiPropertiesTest {

    private static final String GOOD_SECRET = "0123456789abcdef0123456789abcdef";

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "short", "0123456789abcdef0123456789abcde", "                                "})
    void aMissingBlankOrShortSecretIsRejected(String secret) {
        assertThatThrownBy(() -> new InternalApiProperties(secret, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INTERNAL_SHARED_SECRET");
    }

    @Test
    void aThirtyTwoCharacterSecretIsAccepted() {
        assertThat(new InternalApiProperties(GOOD_SECRET, 1024).sharedSecret()).isEqualTo(GOOD_SECRET);
    }

    @Test
    void theSecretIsNeverPrinted() {
        assertThat(new InternalApiProperties(GOOD_SECRET, 1024).toString()).doesNotContain(GOOD_SECRET);
    }

    @Test
    void theContextFailsToStartWhenTheEnvironmentValueIsEmpty() {
        new ApplicationContextRunner()
                .withUserConfiguration(Properties.class)
                .withPropertyValues("pulseboard.internal.shared-secret=", "pulseboard.internal.max-body-bytes=1024")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                    assertThat(rootMessage(context.getStartupFailure())).contains("INTERNAL_SHARED_SECRET");
                });
    }

    @Test
    void theContextFailsToStartWhenTheSecretIsTooShort() {
        new ApplicationContextRunner()
                .withUserConfiguration(Properties.class)
                .withPropertyValues("pulseboard.internal.shared-secret=too-short",
                        "pulseboard.internal.max-body-bytes=1024")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theContextStartsWithAValidSecret() {
        new ApplicationContextRunner()
                .withUserConfiguration(Properties.class)
                .withPropertyValues("pulseboard.internal.shared-secret=" + GOOD_SECRET,
                        "pulseboard.internal.max-body-bytes=1024")
                .run(context -> assertThat(context.getBean(InternalApiProperties.class).sharedSecret())
                        .isEqualTo(GOOD_SECRET));
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    @EnableConfigurationProperties(InternalApiProperties.class)
    static class Properties {
    }
}
