package io.pulseboard.core.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL treats {@code statement_timeout = 0} as "no timeout", so a configured timeout that would be sent as
 * zero milliseconds must be rejected at startup instead of silently disabling the limit.
 */
class JdbcMetricSampleRepositoryTimeoutConfigTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    void aSubMillisecondTimeoutIsRejected() {
        assertThatThrownBy(() -> new JdbcMetricSampleRepository(jdbcTemplate, Duration.parse("PT0.0005S")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statement-timeout");
    }

    @Test
    void zeroAndNegativeTimeoutsAreRejected() {
        assertThatThrownBy(() -> new JdbcMetricSampleRepository(jdbcTemplate, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcMetricSampleRepository(jdbcTemplate, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneMillisecondIsAccepted() {
        assertThatCode(() -> new JdbcMetricSampleRepository(jdbcTemplate, Duration.ofMillis(1)))
                .doesNotThrowAnyException();
    }
}
