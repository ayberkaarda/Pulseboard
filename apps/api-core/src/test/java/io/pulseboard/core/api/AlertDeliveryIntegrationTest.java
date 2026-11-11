package io.pulseboard.core.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.domain.alert.AlertOutbox;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.AlertIntegrationTest;
import io.pulseboard.core.support.WebhookReceiver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Delivery of pending events: retries, final failures, redirects, timeouts, crash recovery, no leaks. */
@ExtendWith(OutputCaptureExtension.class)
class AlertDeliveryIntegrationTest extends AlertIntegrationTest {

    @Autowired
    private AlertOutbox outbox;

    private record Hook(TenantFixture tenant, AlertRuleResponse rule, String path) {
    }

    /** A tenant with a webhook rule that ingestion will not trigger (huge threshold); events are inserted by hand. */
    private Hook hook() {
        TenantFixture tenant = newTenant();
        String path = hookPath();
        AlertRuleResponse rule = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "hook " + UUID.randomUUID(), "GT", 1e99, WebhookReceiver.url(path)));
        return new Hook(tenant, rule, path);
    }

    private static void respond(String path, int status) {
        WebhookReceiver.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status)
                .withBody("secret receiver text " + status)));
    }

    private Map<String, Object> row(UUID eventId) {
        return jdbc.queryForMap("SELECT delivery_status, attempts, last_error, delivered_at, claimed_until, "
                + "next_attempt_at FROM alert_event WHERE id = ?", eventId);
    }

    @Test
    void aServerErrorIsRetriedWithBackoffUntilTheReceiverRecovers() {
        Hook hook = hook();
        String scenario = hook.path();
        WebhookReceiver.server().stubFor(post(urlEqualTo(hook.path())).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        WebhookReceiver.server().stubFor(post(urlEqualTo(hook.path())).inScenario(scenario)
                .whenScenarioStateIs("recovered").willReturn(aResponse().withStatus(200)));
        UUID eventId = insertEvent(hook.tenant(), hook.rule().id());

        Instant firstStart = Instant.now();
        assertThat(dispatchService.dispatchDue()).isGreaterThanOrEqualTo(1);
        Instant firstEnd = Instant.now();

        Map<String, Object> afterFirst = row(eventId);
        assertThat(afterFirst.get("delivery_status")).isEqualTo("PENDING");
        assertThat(afterFirst.get("attempts")).isEqualTo(1);
        assertThat(afterFirst.get("last_error")).isEqualTo("HTTP 503");
        assertThat(afterFirst.get("claimed_until")).as("the claim is released").isNull();
        // The backoff is measured from the moment the result was recorded, which lies inside the dispatch call:
        // bounds taken around the call hold however slow the scheduler is.
        Instant next = ((java.sql.Timestamp) afterFirst.get("next_attempt_at")).toInstant();
        Duration backoff = alertProperties.dispatcher().backoff().getFirst();
        assertThat(next).as("rescheduled with the backoff")
                .isAfterOrEqualTo(firstStart.plus(backoff).truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isBeforeOrEqualTo(firstEnd.plus(backoff).plusMillis(1));
        // Not due yet: another poll leaves it alone. Only checked when the poll certainly ran before it was due.
        dispatchService.dispatchDue();
        if (Instant.now().isBefore(next)) {
            assertThat(requests(hook.path())).hasSize(1);
        }

        dispatchUntil(() -> "SENT".equals(status(eventId)));

        Map<String, Object> done = row(eventId);
        assertThat(done.get("attempts")).isEqualTo(2);
        assertThat(done.get("last_error")).as("a success clears the error").isNull();
        assertThat(done.get("delivered_at")).isNotNull();
        assertThat(requests(hook.path())).hasSize(2);
        assertThat(requests(hook.path()).get(1).getHeader("X-Pulseboard-Event-Id")).isEqualTo(eventId.toString());
    }

    @Test
    void anEventIsGivenUpOnAfterTheConfiguredNumberOfAttempts() {
        Hook hook = hook();
        respond(hook.path(), 500);
        UUID eventId = insertEvent(hook.tenant(), hook.rule().id());

        for (int attempt = 1; attempt <= alertProperties.dispatcher().maxAttempts(); attempt++) {
            makeDueNow(eventId);
            dispatchService.dispatchDue();
            assertThat(row(eventId).get("attempts")).isEqualTo(attempt);
        }

        Map<String, Object> row = row(eventId);
        assertThat(row.get("delivery_status")).isEqualTo("FAILED");
        assertThat(row.get("last_error")).isEqualTo("HTTP 500");
        assertThat(row.get("delivered_at")).isNull();
        assertThat(requests(hook.path())).hasSize(alertProperties.dispatcher().maxAttempts());
        // A failed event is never picked up again.
        jdbc.update("UPDATE alert_event SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), eventId);
        dispatchService.dispatchDue();
        assertThat(requests(hook.path())).hasSize(alertProperties.dispatcher().maxAttempts());
    }

    @Test
    void clientErrorsOtherThanTimeoutAndTooManyRequestsFailAtOnceWithoutRetries() {
        for (int code : List.of(400, 401, 403, 404, 410, 422)) {
            Hook hook = hook();
            respond(hook.path(), code);
            UUID eventId = insertEvent(hook.tenant(), hook.rule().id());

            dispatchService.dispatchDue();

            Map<String, Object> row = row(eventId);
            assertThat(row.get("delivery_status")).as("status for " + code).isEqualTo("FAILED");
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat(row.get("last_error")).isEqualTo("HTTP " + code);
            makeDueNow(eventId);
            dispatchService.dispatchDue();
            assertThat(requests(hook.path())).as("requests for " + code).hasSize(1);
        }
    }

    @Test
    void requestTimeoutAndTooManyRequestsAreRetried() {
        for (int code : List.of(408, 429, 502, 504)) {
            Hook hook = hook();
            respond(hook.path(), code);
            UUID eventId = insertEvent(hook.tenant(), hook.rule().id());

            dispatchService.dispatchDue();

            Map<String, Object> row = row(eventId);
            assertThat(row.get("delivery_status")).as("status for " + code).isEqualTo("PENDING");
            assertThat(row.get("last_error")).isEqualTo("HTTP " + code);
        }
    }

    @Test
    void redirectsAreNotFollowedAndFailTheEventAtOnce() {
        for (int code : List.of(301, 302, 307, 308)) {
            Hook hook = hook();
            String elsewhere = hookPath();
            WebhookReceiver.server().stubFor(post(urlEqualTo(hook.path())).willReturn(aResponse().withStatus(code)
                    .withHeader("Location", WebhookReceiver.url(elsewhere))));
            WebhookReceiver.server().stubFor(post(urlEqualTo(elsewhere)).willReturn(aResponse().withStatus(200)));
            UUID eventId = insertEvent(hook.tenant(), hook.rule().id());

            dispatchService.dispatchDue();

            Map<String, Object> row = row(eventId);
            assertThat(row.get("delivery_status")).as("status for " + code).isEqualTo("FAILED");
            assertThat(row.get("last_error")).isEqualTo("HTTP " + code + " (redirects are not followed)");
            assertThat(requests(elsewhere)).as("redirect target for " + code).isEmpty();
        }
    }

    @Test
    void aReceiverThatAnswersTooSlowlyTimesOutAndIsRetried() {
        Hook hook = hook();
        WebhookReceiver.server().stubFor(post(urlEqualTo(hook.path())).willReturn(aResponse().withStatus(200)
                .withFixedDelay(6000)));
        UUID eventId = insertEvent(hook.tenant(), hook.rule().id());
        long started = System.nanoTime();

        dispatchService.dispatchDue();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("bounded by the read timeout (2 s)")
                .isLessThan(Duration.ofSeconds(5));
        Map<String, Object> row = row(eventId);
        assertThat(row.get("delivery_status")).isEqualTo("PENDING");
        assertThat(row.get("last_error")).isEqualTo("timeout");
        assertThat(row.get("attempts")).isEqualTo(1);
    }

    @Test
    void aTargetOnAPortThatIsNotAllowedIsRefusedAtDeliveryWithoutLeakingIt(CapturedOutput output) {
        Hook hook = hook();
        String path = "/other-port/" + UUID.randomUUID();
        jdbc.update("UPDATE alert_rule SET target = ? WHERE id = ?", "http://localhost:1" + path, hook.rule().id());
        UUID eventId = insertEvent(hook.tenant(), hook.rule().id());

        dispatchService.dispatchDue();

        Map<String, Object> row = row(eventId);
        assertThat(row.get("delivery_status")).isEqualTo("FAILED");
        assertThat(row.get("last_error")).isEqualTo("target rejected: port is not allowed");
        assertThat(output.getAll()).doesNotContain(path);
    }

    @Test
    void aClaimThatWasNeverFinishedIsTakenOverAfterItsLease() {
        Hook hook = hook();
        respond(hook.path(), 200);
        UUID eventId = insertEvent(hook.tenant(), hook.rule().id());
        // A dispatcher claims the event and dies.
        Instant now = Instant.now();
        var claimed = outbox.claimDue(now, now.plusSeconds(60), 100, 4).stream()
                .filter(due -> due.eventId().equals(eventId)).toList();
        assertThat(claimed).hasSize(1);

        dispatchService.dispatchDue();
        assertThat(requests(hook.path())).as("still leased to the dead dispatcher").isEmpty();
        assertThat(status(eventId)).isEqualTo("PENDING");

        jdbc.update("UPDATE alert_event SET claimed_until = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), eventId);
        dispatchService.dispatchDue();

        assertThat(status(eventId)).isEqualTo("SENT");
        assertThat(row(eventId).get("attempts")).as("the lost claim counted as an attempt").isEqualTo(2);
        assertThat(requests(hook.path())).hasSize(1);
    }

    @Test
    void anEventWhoseLastAttemptWasLostIsFailedWithoutAnotherDelivery() {
        Hook hook = hook();
        respond(hook.path(), 200);
        UUID eventId = insertEvent(hook.tenant(), hook.rule().id());
        jdbc.update("UPDATE alert_event SET attempts = ?, claimed_until = ? WHERE id = ?",
                alertProperties.dispatcher().maxAttempts(), java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                eventId);

        dispatchService.dispatchDue();

        Map<String, Object> row = row(eventId);
        assertThat(row.get("delivery_status")).isEqualTo("FAILED");
        assertThat(row.get("last_error")).isEqualTo("delivery did not complete");
        assertThat(requests(hook.path())).isEmpty();
    }

    @Test
    void aDisabledRuleDoesNotDeliverEventsThatWereAlreadyPending() {
        Hook hook = hook();
        respond(hook.path(), 200);
        UUID eventId = insertEvent(hook.tenant(), hook.rule().id());
        client.patch().uri("/api/v1/alert-rules/{id}", hook.rule().id())
                .header(HttpHeaders.AUTHORIZATION, bearer(hook.tenant().ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("enabled", false))
                .exchange().expectStatus().isOk();

        dispatchService.dispatchDue();

        assertThat(row(eventId).get("delivery_status")).isEqualTo("FAILED");
        assertThat(row(eventId).get("last_error")).isEqualTo("rule disabled");
        assertThat(requests(hook.path())).isEmpty();
    }

    @Test
    void aTargetThatTurnedUnsafeAfterTheRuleWasSavedIsNotCalled() {
        Hook hook = hook();
        // The stored target is changed behind the API's back to something the guard refuses at delivery time.
        for (String unsafe : List.of("http://169.254.169.254/latest/meta-data/", "http://localhost:" + 22 + "/x",
                "http://user:pw@localhost:" + WebhookReceiver.port() + "/x", "ftp://localhost/x",
                "http://[::1]:" + WebhookReceiver.port() + "/x")) {
            jdbc.update("UPDATE alert_rule SET target = ? WHERE id = ?", unsafe, hook.rule().id());
            UUID eventId = insertEvent(hook.tenant(), hook.rule().id());

            dispatchService.dispatchDue();

            Map<String, Object> row = row(eventId);
            assertThat(row.get("delivery_status")).as(unsafe).isEqualTo("FAILED");
            assertThat(row.get("last_error").toString()).as(unsafe).startsWith("target rejected: ")
                    .doesNotContain("169.254").doesNotContain("pw");
        }
    }

    @Test
    void logsAndStoredErrorsNameNeitherTheTargetNorTheSecretNorTheReceiversText(CapturedOutput output) {
        Hook hook = hook();
        respond(hook.path(), 500);
        UUID failing = insertEvent(hook.tenant(), hook.rule().id());
        dispatchService.dispatchDue();
        Hook good = hook();
        respond(good.path(), 200);
        UUID succeeding = insertEvent(good.tenant(), good.rule().id());
        dispatchService.dispatchDue();

        assertThat(status(succeeding)).isEqualTo("SENT");
        assertThat(row(failing).get("last_error")).isEqualTo("HTTP 500");
        String logs = output.getAll();
        assertThat(logs).contains("Alert delivery: event=" + failing).contains("Alert delivery: event=" + succeeding);
        assertThat(logs).doesNotContain(signingSecret())
                .doesNotContain(hook.path()).doesNotContain(good.path())
                .doesNotContain("secret receiver text")
                .doesNotContain(requests(good.path()).getFirst().getHeader("X-Pulseboard-Signature"));
    }

    @Test
    void anEmailLogRuleWritesOneStructuredLineWithoutInjectableText(CapturedOutput output) {
        TenantFixture tenant = newTenant();
        Map<String, Object> body = thresholdBody(tenant.metricId(), "mail rule", "GT", 1e99, "ops@example.com");
        body.put("channel", "EMAIL_LOG");
        AlertRuleResponse rule = createRule(tenant.ownerToken(), body);
        UUID eventId = insertEvent(tenant, rule.id());

        dispatchService.dispatchDue();

        assertThat(status(eventId)).isEqualTo("SENT");
        List<String> lines = output.getAll().lines().filter(line -> line.contains("alert_email")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("event=" + eventId).contains("to=\"ops@example.com\"")
                .contains("rule=\"mail rule\"").contains("tenant=" + tenant.tenantId());
    }

    @Test
    void aWorkerWhoseLeaseWasTakenOverCannotChangeTheNewClaimsEvent() {
        Hook hook = hook();
        UUID eventId = insertEvent(hook.tenant(), hook.rule().id());
        TenantId tenantId = TenantId.of(hook.tenant().tenantId());
        Instant now = Instant.now();
        var stale = outbox.claimDue(now, now.plusSeconds(60), 1000, 4).stream()
                .filter(due -> due.eventId().equals(eventId)).findFirst().orElseThrow();
        // The first worker hangs; its lease runs out and a second worker claims the same event.
        jdbc.update("UPDATE alert_event SET claimed_until = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), eventId);
        Instant later = Instant.now();
        var current = outbox.claimDue(later, later.plusSeconds(120), 1000, 4).stream()
                .filter(due -> due.eventId().equals(eventId)).findFirst().orElseThrow();
        assertThat(current.claimedUntil()).isNotEqualTo(stale.claimedUntil());
        Map<String, Object> before = row(eventId);
        assertThat(before.get("attempts")).isEqualTo(2);

        Instant soon = Instant.now().plusSeconds(5);
        assertThat(outbox.markSent(eventId, tenantId, stale.claimedUntil(), Instant.now())).isFalse();
        assertThat(outbox.markFailed(eventId, tenantId, stale.claimedUntil(), "late")).isFalse();
        assertThat(outbox.reschedule(eventId, tenantId, stale.claimedUntil(), soon, "late")).isFalse();
        assertThat(outbox.defer(eventId, tenantId, stale.claimedUntil(), soon)).isFalse();

        assertThat(row(eventId)).as("the stale worker changed nothing").isEqualTo(before);
        assertThat(outbox.defer(eventId, tenantId, current.claimedUntil(), soon)).as("the owner may").isTrue();
        assertThat(row(eventId).get("attempts")).isEqualTo(1);
        // A stale result for an event that was meanwhile finished by its owner is ignored as well.
        var third = outbox.claimDue(soon.plusSeconds(1), soon.plusSeconds(100), 1000, 4).stream()
                .filter(due -> due.eventId().equals(eventId)).findFirst().orElseThrow();
        assertThat(outbox.markSent(eventId, tenantId, third.claimedUntil(), Instant.now())).isTrue();
        assertThat(outbox.markFailed(eventId, tenantId, third.claimedUntil(), "again")).isFalse();
        assertThat(status(eventId)).isEqualTo("SENT");
    }

    @Test
    void outboxClaimsAreDisjointBetweenConcurrentDispatchers() throws Exception {
        TenantFixture tenant = newTenant();
        Map<String, Object> body = thresholdBody(tenant.metricId(), "bulk", "GT", 1e99, "ops@example.com");
        body.put("channel", "EMAIL_LOG");
        AlertRuleResponse rule = createRule(tenant.ownerToken(), body);
        // Isolate from other tests' pending events: only count what this test created.
        List<UUID> mine = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            mine.add(insertEvent(tenant, rule.id()));
        }
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<List<UUID>> claim = () -> {
                barrier.await();
                Instant now = Instant.now();
                return outbox.claimDue(now, now.plusSeconds(120), 25, 4).stream().map(due -> due.eventId())
                        .filter(mine::contains).toList();
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            List<UUID> a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            List<UUID> b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(a).doesNotContainAnyElementsOf(b);
            assertThat(a.size() + b.size()).isLessThanOrEqualTo(40);
            assertThat(a).doesNotHaveDuplicates();
            assertThat(b).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rowsLockedByAnotherDispatcherAreSkippedNotWaitedFor() throws Exception {
        TenantFixture tenant = newTenant();
        Map<String, Object> body = thresholdBody(tenant.metricId(), "locked", "GT", 1e99, "ops@example.com");
        body.put("channel", "EMAIL_LOG");
        AlertRuleResponse rule = createRule(tenant.ownerToken(), body);
        List<UUID> events = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            events.add(insertEvent(tenant, rule.id()));
        }
        List<UUID> locked = events.subList(0, 3);
        javax.sql.DataSource dataSource = jdbc.getDataSource();
        try (java.sql.Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (java.sql.PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM alert_event WHERE id = ANY (?) FOR UPDATE")) {
                lock.setArray(1, holder.createArrayOf("uuid", locked.toArray()));
                lock.executeQuery().close();

                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    var claimed = pool.submit(() -> {
                        Instant now = Instant.now();
                        return outbox.claimDue(now, now.plusSeconds(120), 1000, 4).stream()
                                .map(due -> due.eventId()).filter(events::contains).toList();
                    }).get(10, java.util.concurrent.TimeUnit.SECONDS);

                    assertThat(claimed).containsExactlyInAnyOrderElementsOf(events.subList(3, 6));
                } finally {
                    pool.shutdownNow();
                }
            } finally {
                holder.rollback();
            }
        }
    }

}
