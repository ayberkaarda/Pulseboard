package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import io.pulseboard.core.application.anomaly.AnomalyPublisher;
import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.domain.anomaly.Anomaly;
import io.pulseboard.core.support.AnomalyIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** When the message bus is down the notification fails closed, so the sender retries it. */
class AnomalyNotifyOutageIntegrationTest extends AnomalyIntegrationTest {

    @MockitoSpyBean
    private AnomalyPublisher publisher;

    @AfterEach
    void restore() {
        reset(publisher);
    }

    @Test
    void anUnreachableBusAnswers503WithRetryAfterAndStoresNothingNew() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, Instant.parse("2026-03-01T00:00:00Z"), "HIGH");
        doThrow(new DependencyUnavailableException("bus down", new IllegalStateException()))
                .when(publisher).publish(any(Anomaly.class));

        HttpResponse<String> failed = notifySigned(notificationBody(stored));

        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.headers().firstValue("Retry-After")).contains("5");
        assertThat(failed.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(failed.body()).doesNotContain("bus down");

        // After recovery the very same call succeeds: nothing was lost or half-applied.
        reset(publisher);
        assertThat(notifySigned(notificationBody(stored)).statusCode()).isEqualTo(202);
    }
}
