package io.pulseboard.core.application.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.application.alert.DeliveryOutcome.Kind;
import io.pulseboard.core.application.alert.webhook.HostResolver;
import io.pulseboard.core.application.alert.webhook.ResolvedWebhookTarget;
import io.pulseboard.core.application.alert.webhook.WebhookPolicy;
import io.pulseboard.core.application.alert.webhook.WebhookSender;
import io.pulseboard.core.application.alert.webhook.WebhookSigner;
import io.pulseboard.core.application.alert.webhook.WebhookTargetGuard;
import io.pulseboard.core.application.alert.webhook.WebhookTransportException;
import io.pulseboard.core.domain.alert.AlertChannel;
import io.pulseboard.core.domain.alert.DueAlert;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.MutableClock;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The delivery decision logic with a scripted receiver: statuses, pinning, signing, breaker. */
class AlertDeliveryServiceTest {

    private static final String SECRET = "unit-test-signing-secret-0123456789";
    private static final String URL = "https://hooks.example.com/services/T000/B000/very-secret-token";

    private record Call(ResolvedWebhookTarget target, Map<String, String> headers, byte[] body) {
    }

    private static final class ScriptedSender implements WebhookSender {

        private final List<Call> calls = new ArrayList<>();
        private int status = 200;
        private String transportFailure;

        @Override
        public int post(ResolvedWebhookTarget target, Map<String, String> headers, byte[] body)
                throws WebhookTransportException {
            calls.add(new Call(target, headers, body));
            if (transportFailure != null) {
                throw new WebhookTransportException(transportFailure);
            }
            return status;
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-05T12:00:00Z"));
    private final ScriptedSender sender = new ScriptedSender();
    private final AtomicInteger lookups = new AtomicInteger();
    private List<String> answer = List.of("93.184.216.34");
    private boolean unknownHost;

    private final HostResolver resolver = host -> {
        lookups.incrementAndGet();
        if (unknownHost) {
            throw new UnknownHostException(host);
        }
        List<InetAddress> addresses = new ArrayList<>();
        for (String literal : answer) {
            addresses.add(InetAddress.getByName(literal));
        }
        return addresses;
    };

    private AlertDeliveryService service(String secret, int breakerThreshold) {
        WebhookPolicy policy = new WebhookPolicy(secret, false, false, Set.of(443), Duration.ofSeconds(5),
                Duration.ofSeconds(5));
        AlertDispatchPolicy dispatch = new AlertDispatchPolicy(10, Duration.ofSeconds(60), 4,
                List.of(Duration.ofSeconds(10)), breakerThreshold, Duration.ofSeconds(60), Duration.ofSeconds(20));
        return new AlertDeliveryService(new WebhookTargetGuard(policy, resolver), sender, policy,
                new HostCircuitBreaker(dispatch, clock), JsonMapper.builder().build(), clock);
    }

    private static DueAlert alert(String target) {
        UUID id = UUID.randomUUID();
        return new DueAlert(id, TenantId.of(UUID.randomUUID()), UUID.randomUUID(), "rule", true,
                AlertChannel.WEBHOOK, target, Map.of("eventId", id.toString(), "ruleName", "rule", "value", 12.5), 1);
    }

    @Test
    void theRequestIsSignedOverTheTimestampAndTheExactBodyThatIsSent() {
        AlertDeliveryService service = service(SECRET, 5);
        DueAlert alert = alert(URL);

        DeliveryOutcome outcome = service.deliver(alert);

        assertThat(outcome.kind()).isEqualTo(Kind.SENT);
        Call call = sender.calls.getFirst();
        String timestamp = call.headers().get("X-Pulseboard-Timestamp");
        assertThat(timestamp).isEqualTo(Long.toString(clock.instant().getEpochSecond()));
        assertThat(call.headers().get("X-Pulseboard-Signature"))
                .isEqualTo(WebhookSigner.sign(SECRET, timestamp, call.body()));
        assertThat(call.headers().get("X-Pulseboard-Event-Id")).isEqualTo(alert.eventId().toString());
        assertThat(call.headers().get("Content-Type")).isEqualTo("application/json");
        String body = new String(call.body(), StandardCharsets.UTF_8);
        assertThat(body).contains(alert.eventId().toString()).doesNotContain(SECRET).doesNotContain("very-secret-token")
                .doesNotContain("hooks.example.com");
        // A different body or timestamp gives a different signature.
        assertThat(WebhookSigner.sign(SECRET, timestamp, "{}".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(call.headers().get("X-Pulseboard-Signature"));
        assertThat(WebhookSigner.sign(SECRET, "1", call.body()))
                .isNotEqualTo(call.headers().get("X-Pulseboard-Signature"));
    }

    @Test
    void theKnownSignatureOfAFixedMessageIsReproduced() {
        assertThat(WebhookSigner.sign("signing-secret-0123456789abcdef-0123", "1700000000",
                "{\"eventId\":\"e\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("6fa70f77d6e6978e035c66044ed58da38e52413e5dfdf7f34854335a38682c40");
    }

    @Test
    void statusCodesDecideBetweenSuccessRetryAndGivingUp() {
        record Expect(int status, Kind kind, String error) {
        }

        List<Expect> table = List.of(
                new Expect(200, Kind.SENT, null), new Expect(201, Kind.SENT, null), new Expect(204, Kind.SENT, null),
                new Expect(299, Kind.SENT, null),
                new Expect(100, Kind.RETRY, "HTTP 100"),
                new Expect(301, Kind.FAILED, "HTTP 301 (redirects are not followed)"),
                new Expect(302, Kind.FAILED, "HTTP 302 (redirects are not followed)"),
                new Expect(308, Kind.FAILED, "HTTP 308 (redirects are not followed)"),
                new Expect(400, Kind.FAILED, "HTTP 400"), new Expect(401, Kind.FAILED, "HTTP 401"),
                new Expect(403, Kind.FAILED, "HTTP 403"), new Expect(404, Kind.FAILED, "HTTP 404"),
                new Expect(410, Kind.FAILED, "HTTP 410"), new Expect(413, Kind.FAILED, "HTTP 413"),
                new Expect(408, Kind.RETRY, "HTTP 408"), new Expect(429, Kind.RETRY, "HTTP 429"),
                new Expect(500, Kind.RETRY, "HTTP 500"), new Expect(502, Kind.RETRY, "HTTP 502"),
                new Expect(503, Kind.RETRY, "HTTP 503"), new Expect(599, Kind.RETRY, "HTTP 599"));
        for (Expect expect : table) {
            sender.status = expect.status();
            DeliveryOutcome outcome = service(SECRET, 1000).deliver(alert(URL));
            assertThat(outcome.kind()).as("status " + expect.status()).isEqualTo(expect.kind());
            assertThat(outcome.error()).as("status " + expect.status()).isEqualTo(expect.error());
        }
    }

    @Test
    void networkFailuresAreRetriedWithTheSanitisedReason() {
        sender.transportFailure = "timeout";

        DeliveryOutcome outcome = service(SECRET, 5).deliver(alert(URL));

        assertThat(outcome.kind()).isEqualTo(Kind.RETRY);
        assertThat(outcome.error()).isEqualTo("timeout");
    }

    @Test
    void theHostIsLookedUpOncePerDeliveryAndTheCheckedAddressIsTheOneUsed() {
        AlertDeliveryService service = service(SECRET, 5);
        lookups.set(0);

        service.deliver(alert(URL));

        assertThat(lookups).hasValue(1);
        List<InetAddress> pinned = sender.calls.getFirst().target().addresses();
        assertThat(pinned).hasSize(1);
        assertThat(pinned.getFirst().getHostAddress()).isEqualTo("93.184.216.34");

        // The record now points at an internal address: the next delivery looks again and refuses.
        answer = List.of("169.254.169.254");
        DeliveryOutcome outcome = service.deliver(alert(URL));

        assertThat(outcome.kind()).isEqualTo(Kind.FAILED);
        assertThat(outcome.error()).isEqualTo("target rejected: host resolves to a blocked address");
        assertThat(sender.calls).hasSize(1);
        assertThat(lookups).hasValue(2);
    }

    @Test
    void aTargetThatResolvesToAPrivateAddressIsNeverCalled() {
        for (String blocked : List.of("127.0.0.1", "10.0.0.1", "172.16.5.5", "192.168.0.10", "169.254.169.254",
                "100.64.1.1", "::1", "fe80::1", "fd00::1", "::ffff:10.0.0.1")) {
            answer = List.of(blocked);

            DeliveryOutcome outcome = service(SECRET, 1000).deliver(alert(URL));

            assertThat(outcome.kind()).as(blocked).isEqualTo(Kind.FAILED);
            assertThat(outcome.error()).as(blocked).doesNotContain(blocked);
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void aNameThatDoesNotResolveIsRetriedAndNotCalled() {
        unknownHost = true;

        DeliveryOutcome outcome = service(SECRET, 5).deliver(alert(URL));

        assertThat(outcome.kind()).isEqualTo(Kind.RETRY);
        assertThat(outcome.error()).isEqualTo("host cannot be resolved");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void withoutASigningSecretWebhooksAreNotSent() {
        DeliveryOutcome outcome = service("", 5).deliver(alert(URL));

        assertThat(outcome.kind()).isEqualTo(Kind.FAILED);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void aDisabledRuleIsNotDelivered() {
        DueAlert alert = alert(URL);
        DueAlert disabled = new DueAlert(alert.eventId(), alert.tenantId(), alert.ruleId(), alert.ruleName(), false,
                alert.channel(), alert.target(), alert.payload(), 1);

        assertThat(service(SECRET, 5).deliver(disabled).kind()).isEqualTo(Kind.FAILED);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void afterRepeatedFailuresTheHostIsPostponedWithoutCallingItAndRecoversOnSuccess() {
        AlertDeliveryService service = service(SECRET, 2);
        sender.status = 503;

        assertThat(service.deliver(alert(URL)).kind()).isEqualTo(Kind.RETRY);
        assertThat(service.deliver(alert(URL)).kind()).isEqualTo(Kind.RETRY);
        DeliveryOutcome paused = service.deliver(alert(URL));

        assertThat(paused.kind()).isEqualTo(Kind.DEFER);
        assertThat(paused.until()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(sender.calls).hasSize(2);
        // Another host is not affected.
        assertThat(service.deliver(alert("https://other.example.org/hook")).kind()).isEqualTo(Kind.RETRY);
        assertThat(sender.calls).hasSize(3);

        clock.advance(Duration.ofSeconds(61));
        sender.status = 200;
        assertThat(service.deliver(alert(URL)).kind()).isEqualTo(Kind.SENT);
        sender.status = 503;
        // The success closed the breaker completely: it takes two new failures to open it again.
        assertThat(service.deliver(alert(URL)).kind()).isEqualTo(Kind.RETRY);
        assertThat(service.deliver(alert(URL)).kind()).isEqualTo(Kind.RETRY);
        assertThat(service.deliver(alert(URL)).kind()).isEqualTo(Kind.DEFER);
    }

    @Test
    void aFirstFailureAfterThePauseOpensTheBreakerAgainAtOnce() {
        AlertDeliveryService service = service(SECRET, 2);
        sender.status = 500;
        service.deliver(alert(URL));
        service.deliver(alert(URL));
        assertThat(service.deliver(alert(URL)).kind()).isEqualTo(Kind.DEFER);

        clock.advance(Duration.ofSeconds(61));

        assertThat(service.deliver(alert(URL)).kind()).as("the probe").isEqualTo(Kind.RETRY);
        assertThat(service.deliver(alert(URL)).kind()).as("failed probe pauses again").isEqualTo(Kind.DEFER);
    }

    @Test
    void anEmailLogAlertNeedsNoNetwork() {
        DueAlert alert = alert("ops@example.com");
        DueAlert mail = new DueAlert(alert.eventId(), alert.tenantId(), alert.ruleId(), alert.ruleName(), true,
                AlertChannel.EMAIL_LOG, "ops@example.com", alert.payload(), 1);

        assertThat(service("", 5).deliver(mail).kind()).isEqualTo(Kind.SENT);
        assertThat(sender.calls).isEmpty();
        assertThat(lookups).hasValue(0);
    }

    @Test
    void dueAlertsAndPoliciesNeverPrintTheirSecrets() {
        DueAlert alert = alert(URL);

        assertThat(alert.toString()).doesNotContain("very-secret-token").doesNotContain("hooks.example.com");
        assertThat(new WebhookPolicy(SECRET, false, false, Set.of(443), Duration.ofSeconds(1), Duration.ofSeconds(1))
                .toString()).doesNotContain(SECRET);
    }
}
