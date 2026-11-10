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
    void masksPercentEncodedMixedCaseAndDoublyEncodedNames() {
        assertThat(SecretRedactingMessageConverter.redact("GET /s?%74icket=" + SECRET))
                .isEqualTo("GET /s?%74icket=***");
        assertThat(SecretRedactingMessageConverter.redact("GET /s?ti%63ket=" + SECRET + "&x=1"))
                .isEqualTo("GET /s?ti%63ket=***&x=1");
        assertThat(SecretRedactingMessageConverter.redact("GET /s?%54iCkEt=" + SECRET))
                .isEqualTo("GET /s?%54iCkEt=***");
        assertThat(SecretRedactingMessageConverter.redact("GET /s?%2574icket=" + SECRET))
                .isEqualTo("GET /s?%2574icket=***");
        assertThat(SecretRedactingMessageConverter.redact("GET /s?%252574icket=" + SECRET))
                .isEqualTo("GET /s?%252574icket=***");
        assertThat(SecretRedactingMessageConverter.redact("GET /s?%61ccess%5ftoken=" + SECRET))
                .isEqualTo("GET /s?%61ccess%5ftoken=***");
        assertThat(SecretRedactingMessageConverter.redact("/s?a=1&%74icket=" + SECRET + "&%54icket=" + SECRET))
                .isEqualTo("/s?a=1&%74icket=***&%54icket=***");
    }

    @Test
    void masksTokenJwtAndRefreshTokenFields() {
        assertThat(SecretRedactingMessageConverter.redact("/s?token=" + SECRET + "&jwt=" + SECRET))
                .isEqualTo("/s?token=***&jwt=***");
        assertThat(SecretRedactingMessageConverter.redact("TokenResponse[accessToken=" + SECRET
                + ", refreshToken=" + SECRET + ", role=OWNER]"))
                .isEqualTo("TokenResponse[accessToken=***, refreshToken=***, role=OWNER]");
        assertThat(SecretRedactingMessageConverter.redact("/s?refresh_token=" + SECRET + "&refresh-token=" + SECRET))
                .isEqualTo("/s?refresh_token=***&refresh-token=***");
        assertThat(SecretRedactingMessageConverter.redact("{\"refreshToken\":\"" + SECRET + "\",\"a\": 1}"))
                .isEqualTo("{\"refreshToken\":\"***\",\"a\": 1}");
    }

    @Test
    void doesNotTouchLookalikeNamesOrBrokenEscapes() {
        assertThat(SecretRedactingMessageConverter.redact("/s?%74icketing=" + SECRET))
                .isEqualTo("/s?%74icketing=" + SECRET);
        assertThat(SecretRedactingMessageConverter.redact("/s?%7ticket=" + SECRET))
                .isEqualTo("/s?%7ticket=" + SECRET);
        assertThat(SecretRedactingMessageConverter.redact("/s?mytoken=" + SECRET)).isEqualTo("/s?mytoken=" + SECRET);
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
