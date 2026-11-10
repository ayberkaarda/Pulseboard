package io.pulseboard.core.infrastructure.alert;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.pulseboard.core.application.alert.webhook.ResolvedWebhookTarget;
import io.pulseboard.core.application.alert.webhook.WebhookPolicy;
import io.pulseboard.core.application.alert.webhook.WebhookTarget;
import io.pulseboard.core.application.alert.webhook.WebhookTransportException;
import io.pulseboard.core.support.WebhookReceiver;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The HTTP call itself: pinned address, no redirects, no body reads, timeouts. Runs against the shared WireMock
 * server on the loopback address; the target host names below do not exist in DNS, so a request can only arrive
 * through the pinned address.
 */
class ApacheWebhookSenderTest {

    private static ApacheWebhookSender sender(Duration connect, Duration read) {
        return new ApacheWebhookSender(new WebhookPolicy("k".repeat(32), true, true, Set.of(WebhookReceiver.port()),
                connect, read));
    }

    private static ResolvedWebhookTarget target(String host, String path) throws Exception {
        URI uri = URI.create("http://" + host + ":" + WebhookReceiver.port() + path);
        return new ResolvedWebhookTarget(new WebhookTarget(uri, host, WebhookReceiver.port()),
                List.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})));
    }

    private static String path() {
        return "/sender/" + UUID.randomUUID();
    }

    private static List<LoggedRequest> received(String path) {
        return WebhookReceiver.server().findAll(postRequestedFor(urlEqualTo(path)));
    }

    @Test
    void theRequestGoesToThePinnedAddressWhileTheUrlHostStaysTheHostHeader() throws Exception {
        String path = path();
        WebhookReceiver.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(204)));
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        int status = sender(Duration.ofSeconds(2), Duration.ofSeconds(2)).post(
                target("rebind-victim.example.test", path), Map.of("X-Pulseboard-Event-Id", "e-1",
                        "Content-Type", "application/json"), body);

        assertThat(status).isEqualTo(204);
        LoggedRequest request = received(path).getFirst();
        assertThat(request.getHeader("Host")).isEqualTo("rebind-victim.example.test:" + WebhookReceiver.port());
        assertThat(request.getHeader("User-Agent")).isEqualTo("Pulseboard-Alerts");
        assertThat(request.getHeader("X-Pulseboard-Event-Id")).isEqualTo("e-1");
        assertThat(request.getBody()).isEqualTo(body);
    }

    @Test
    void aHostThatWasNotPinnedIsNeverLookedUpInDns() throws Exception {
        // No pinned address for the name in the URL: the resolver of the call refuses to ask DNS for anything else.
        URI uri = URI.create("http://unpinned.example.test:" + WebhookReceiver.port() + path());
        ResolvedWebhookTarget mismatched = new ResolvedWebhookTarget(
                new WebhookTarget(uri, "other-host.example.test", WebhookReceiver.port()),
                List.of(InetAddress.getByAddress("other-host.example.test", new byte[] {127, 0, 0, 1})));

        assertThatThrownBy(() -> sender(Duration.ofSeconds(2), Duration.ofSeconds(2))
                .post(mismatched, Map.of(), new byte[0]))
                .isInstanceOf(WebhookTransportException.class);
    }

    @Test
    void redirectsAreReportedAndNeverFollowed() throws Exception {
        for (int code : List.of(301, 302, 303, 307, 308)) {
            String path = path();
            String elsewhere = path();
            WebhookReceiver.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(code)
                    .withHeader("Location", WebhookReceiver.url(elsewhere))));
            WebhookReceiver.server().stubFor(post(urlEqualTo(elsewhere)).willReturn(aResponse().withStatus(200)));
            WebhookReceiver.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                    urlEqualTo(elsewhere)).willReturn(aResponse().withStatus(200)));

            int status = sender(Duration.ofSeconds(2), Duration.ofSeconds(2))
                    .post(target("localhost", path), Map.of(), "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(status).as("status for " + code).isEqualTo(code);
            assertThat(received(path)).hasSize(1);
            assertThat(received(elsewhere)).as("redirect target for " + code).isEmpty();
            assertThat(WebhookReceiver.server().findAll(com.github.tomakehurst.wiremock.client.WireMock
                    .getRequestedFor(urlEqualTo(elsewhere)))).isEmpty();
        }
    }

    @Test
    void aRedirectToTheMetadataAddressIsNotFollowed() throws Exception {
        String path = path();
        WebhookReceiver.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://169.254.169.254/latest/meta-data/iam/security-credentials/")));

        int status = sender(Duration.ofSeconds(2), Duration.ofSeconds(2))
                .post(target("localhost", path), Map.of(), "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(status).isEqualTo(302);
        assertThat(received(path)).hasSize(1);
    }

    @Test
    void aSlowReceiverEndsWithATimeoutBeforeItAnswers() throws Exception {
        String path = path();
        WebhookReceiver.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(5000)));
        long started = System.nanoTime();

        assertThatThrownBy(() -> sender(Duration.ofSeconds(1), Duration.ofMillis(500))
                .post(target("localhost", path), Map.of(), "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WebhookTransportException.class)
                .hasMessage("timeout");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void theResponseBodyIsNotReadSoAHugeOneCostsNothing() throws Exception {
        String path = path();
        byte[] huge = new byte[32 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) 'x');
        WebhookReceiver.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(500)
                .withBody(huge)));
        long started = System.nanoTime();

        int status = sender(Duration.ofSeconds(2), Duration.ofSeconds(2))
                .post(target("localhost", path), Map.of(), "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(status).isEqualTo(500);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aClosedPortIsANetworkErrorThatNamesNoHost() throws Exception {
        URI uri = URI.create("http://closed.example.test:1/secret-path?token=abc");
        ResolvedWebhookTarget closed = new ResolvedWebhookTarget(new WebhookTarget(uri, "closed.example.test", 1),
                List.of(InetAddress.getByAddress("closed.example.test", new byte[] {127, 0, 0, 1})));

        assertThatThrownBy(() -> sender(Duration.ofSeconds(1), Duration.ofSeconds(1))
                .post(closed, Map.of(), new byte[0]))
                .isInstanceOf(WebhookTransportException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).startsWith("network error: ")
                        .doesNotContain("closed.example.test").doesNotContain("secret-path").doesNotContain("abc"));
    }

    @Test
    void theSignatureHeaderValueIsPassedThroughAsGiven() throws Exception {
        String path = path();
        WebhookReceiver.server().stubFor(post(urlEqualTo(path)).withHeader("X-Pulseboard-Signature", equalTo("abc123"))
                .willReturn(aResponse().withStatus(200)));

        int status = sender(Duration.ofSeconds(2), Duration.ofSeconds(2)).post(target("localhost", path),
                Map.of("X-Pulseboard-Signature", "abc123"), new byte[0]);

        assertThat(status).isEqualTo(200);
    }
}
