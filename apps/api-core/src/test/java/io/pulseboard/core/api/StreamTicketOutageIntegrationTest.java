package io.pulseboard.core.api;

import static io.pulseboard.core.support.StreamTicketTestSupport.openWithTicket;
import static io.pulseboard.core.support.StreamTicketTestSupport.requestTicket;
import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.AnomalyIntegrationTest;
import io.pulseboard.core.support.SseTestClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Tickets fail closed when Redis is down. Own context; the test stops that context's Redis container. */
@TestPropertySource(properties = "pulseboard.stream.ticket.issue-capacity=40")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StreamTicketOutageIntegrationTest extends AnomalyIntegrationTest {

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redisContainer;

    @Test
    void issuingAndUsingATicketAnswer503WhileRedisIsDown() {
        TenantFixture tenant = newTenant();
        String accessToken = tenant.ownerToken();

        redisContainer.stop();

        HttpResponse<String> issued = requestTicket(port, "Authorization", "Bearer " + accessToken);
        assertThat(issued.statusCode()).isEqualTo(503);
        assertThat(issued.headers().firstValue("Retry-After")).isPresent();
        assertThat(issued.body()).contains("urn:pulseboard:problem:unavailable").doesNotContain("\"ticket\"");
        try (SseTestClient stream = openWithTicket(port, "A".repeat(43))) {
            assertThat(stream.status()).isEqualTo(503);
            assertThat(stream.header("Retry-After")).isNotNull();
            assertThat(stream.errorBody()).contains("urn:pulseboard:problem:unavailable");
        }
    }
}
