package io.pulseboard.core.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretRedactingMessageConverterTest {

    private static final String SECRET = "k3Jx9_-AbC123";

    @Test
    void masksTheValueOfTheTicketParameterWhereverItAppears() {
        assertThat(SecretRedactingMessageConverter.redact("Securing GET /api/v1/stream/anomalies?ticket=" + SECRET))
                .isEqualTo("Securing GET /api/v1/stream/anomalies?ticket=***");
        assertThat(SecretRedactingMessageConverter.redact("GET \"/s?a=1&ticket=" + SECRET + "&b=2\" in servlet"))
                .isEqualTo("GET \"/s?a=1&ticket=***&b=2\" in servlet");
        assertThat(SecretRedactingMessageConverter.redact("GET /s?a=1;ticket=" + SECRET + " HTTP/1.1"))
                .isEqualTo("GET /s?a=1;ticket=*** HTTP/1.1");
        assertThat(SecretRedactingMessageConverter.redact("Writing [Response[ticket=" + SECRET + " ]]"))
                .isEqualTo("Writing [Response[ticket=*** ]]");
    }

    @Test
    void masksEveryOccurrenceAndIsCaseInsensitive() {
        assertThat(SecretRedactingMessageConverter.redact("/a?TICKET=" + SECRET + "&Ticket=" + SECRET))
                .isEqualTo("/a?TICKET=***&Ticket=***");
    }

    @Test
    void masksAnAccessTokenInTheQueryString() {
        assertThat(SecretRedactingMessageConverter.redact("GET /s?access_token=" + SECRET))
                .isEqualTo("GET /s?access_token=***");
    }

    @Test
    void leavesEverythingElseAlone() {
        assertThat(SecretRedactingMessageConverter.redact("GET /api/v1/anomalies?size=10&page=2"))
                .isEqualTo("GET /api/v1/anomalies?size=10&page=2");
        assertThat(SecretRedactingMessageConverter.redact("no equals sign")).isEqualTo("no equals sign");
        assertThat(SecretRedactingMessageConverter.redact("the ticketing=on flag")).isEqualTo("the ticketing=on flag");
        assertThat(SecretRedactingMessageConverter.redact(null)).isNull();
        assertThat(SecretRedactingMessageConverter.redact("")).isEmpty();
    }
}
