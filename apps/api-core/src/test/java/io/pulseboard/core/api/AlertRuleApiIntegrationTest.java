package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.AlertIntegrationTest;
import io.pulseboard.core.support.WebhookReceiver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** CRUD, authorisation, tenant isolation, concurrency and validation of the alert rule endpoints. */
@ExtendWith(OutputCaptureExtension.class)
class AlertRuleApiIntegrationTest extends AlertIntegrationTest {

    private static final String RULES = "/api/v1/alert-rules";

    private Map<String, Object> webhookBody(TenantFixture tenant, String name) {
        return thresholdBody(tenant.metricId(), name, "GT", 100, WebhookReceiver.url(hookPath()));
    }

    private RestTestClient.ResponseSpec call(HttpMethod method, String uri, String token, Object body, Object... vars) {
        RestTestClient.RequestBodySpec spec = client.method(method).uri(uri, vars);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange();
    }

    private Map<String, Object> update(AlertRuleResponse rule, long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("metricId", rule.metricId());
        body.put("name", rule.name());
        body.put("condition", "THRESHOLD");
        body.put("operator", "LT");
        body.put("threshold", 5.0);
        body.put("cooldownSeconds", 120);
        body.put("channel", "WEBHOOK");
        body.put("target", rule.target());
        return body;
    }

    @Test
    void aRuleCanBeCreatedReadUpdatedAndDeleted() {
        TenantFixture tenant = newTenant();
        Map<String, Object> request = webhookBody(tenant, "latency high");

        AlertRuleResponse created = createRule(tenant.ownerToken(), request);

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("latency high");
        assertThat(created.condition().name()).isEqualTo("THRESHOLD");
        assertThat(created.operator().name()).isEqualTo("GT");
        assertThat(created.threshold()).isEqualTo(100.0);
        assertThat(created.minSeverity()).isNull();
        assertThat(created.cooldownSeconds()).isEqualTo(60);
        assertThat(created.enabled()).isTrue();
        assertThat(created.version()).isZero();
        assertThat(created.metricName()).isEqualTo("latency_p95_ms");
        assertThat(created.metricUnit()).isEqualTo("ms");
        assertThat(created.serviceName()).isEqualTo("checkout");
        assertThat(created.createdBy()).isEqualTo(tenant.owner().userId());

        AlertRuleResponse read = call(HttpMethod.GET, RULES + "/{id}", tenant.ownerToken(), null, created.id())
                .expectStatus().isOk().expectBody(AlertRuleResponse.class).returnResult().getResponseBody();
        assertThat(read).isEqualTo(created);
        call(HttpMethod.GET, RULES, tenant.ownerToken(), null).expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(created.id().toString());

        AlertRuleResponse updated = call(HttpMethod.PUT, RULES + "/{id}", tenant.ownerToken(),
                update(created, created.version()), created.id())
                .expectStatus().isOk().expectBody(AlertRuleResponse.class).returnResult().getResponseBody();
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.operator().name()).isEqualTo("LT");
        assertThat(updated.threshold()).isEqualTo(5.0);
        assertThat(updated.cooldownSeconds()).isEqualTo(120);
        assertThat(updated.updatedAt()).isAfterOrEqualTo(created.updatedAt());

        // Switching the condition clears the fields of the old one.
        Map<String, Object> toSeverity = severityBody(tenant.metricId(), "latency high", "HIGH", created.target());
        toSeverity.put("version", updated.version());
        AlertRuleResponse switched = call(HttpMethod.PUT, RULES + "/{id}", tenant.ownerToken(), toSeverity,
                created.id()).expectStatus().isOk().expectBody(AlertRuleResponse.class).returnResult()
                .getResponseBody();
        assertThat(switched.operator()).isNull();
        assertThat(switched.threshold()).isNull();
        assertThat(switched.minSeverity().name()).isEqualTo("HIGH");

        call(HttpMethod.DELETE, RULES + "/{id}", tenant.ownerToken(), null, created.id())
                .expectStatus().isNoContent();
        call(HttpMethod.GET, RULES + "/{id}", tenant.ownerToken(), null, created.id()).expectStatus().isNotFound();
        call(HttpMethod.DELETE, RULES + "/{id}", tenant.ownerToken(), null, created.id()).expectStatus().isNotFound();
    }

    @Test
    void anAdminMayWriteAndAViewerMayOnlyRead() {
        TenantFixture tenant = newTenant();
        TokenResponse admin = signInAs(tenant, "admin@example.com", "ADMIN");
        TokenResponse viewer = signInAs(tenant, "viewer@example.com", "VIEWER");
        AlertRuleResponse rule = createRule(tenant.ownerToken(), webhookBody(tenant, "base"));

        AlertRuleResponse byAdmin = createRule(admin.accessToken(), webhookBody(tenant, "by admin"));
        assertThat(byAdmin.createdBy()).isEqualTo(admin.userId());

        call(HttpMethod.GET, RULES, viewer.accessToken(), null).expectStatus().isOk();
        call(HttpMethod.GET, RULES + "/{id}", viewer.accessToken(), null, rule.id()).expectStatus().isOk();
        call(HttpMethod.GET, "/api/v1/alert-events", viewer.accessToken(), null).expectStatus().isOk();
        call(HttpMethod.POST, RULES, viewer.accessToken(), webhookBody(tenant, "viewer rule")).expectStatus()
                .isForbidden();
        call(HttpMethod.PUT, RULES + "/{id}", viewer.accessToken(), update(rule, 0), rule.id()).expectStatus()
                .isForbidden();
        call(HttpMethod.PATCH, RULES + "/{id}", viewer.accessToken(), Map.of("enabled", false), rule.id())
                .expectStatus().isForbidden();
        call(HttpMethod.DELETE, RULES + "/{id}", viewer.accessToken(), null, rule.id()).expectStatus()
                .isForbidden();
        assertThat(call(HttpMethod.GET, RULES + "/{id}", tenant.ownerToken(), null, rule.id())
                .expectBody(AlertRuleResponse.class).returnResult().getResponseBody()).isEqualTo(rule);
    }

    @Test
    void anApiKeyAndAnonymousCallersAreRefused() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = createRule(tenant.ownerToken(), webhookBody(tenant, "base"));
        String key = newApiKey(tenant);

        for (String uri : List.of(RULES, RULES + "/" + rule.id(), "/api/v1/alert-events",
                "/api/v1/alert-events/" + UUID.randomUUID())) {
            client.get().uri(uri).header(API_KEY_HEADER, key).exchange().expectStatus().isForbidden();
            client.get().uri(uri).exchange().expectStatus().isUnauthorized();
        }
        client.post().uri(RULES).header(API_KEY_HEADER, key).contentType(MediaType.APPLICATION_JSON)
                .body(webhookBody(tenant, "by key")).exchange().expectStatus().isForbidden();
        client.delete().uri(RULES + "/{id}", rule.id()).header(API_KEY_HEADER, key).exchange().expectStatus()
                .isForbidden();
        client.post().uri(RULES).contentType(MediaType.APPLICATION_JSON).body(webhookBody(tenant, "anon"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rulesAndEventsOfAnotherTenantAreIndistinguishableFromMissingOnes() {
        TenantFixture owner = newTenant();
        TenantFixture other = newTenant();
        AlertRuleResponse rule = createRule(owner.ownerToken(), webhookBody(owner, "private"));
        ingestNow(newApiKey(owner), owner.metricId(), 500);
        UUID eventId = onlyEvent(rule.id());
        UUID missing = UUID.randomUUID();

        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.DELETE)) {
            String foreign = call(method, RULES + "/{id}", other.ownerToken(), null, rule.id()).expectStatus()
                    .isNotFound().expectBody(String.class).returnResult().getResponseBody();
            String absent = call(method, RULES + "/{id}", other.ownerToken(), null, missing).expectStatus()
                    .isNotFound().expectBody(String.class).returnResult().getResponseBody();
            assertThat(foreign.replace(rule.id().toString(), "ID")).isEqualTo(absent.replace(missing.toString(), "ID"));
        }
        // Whatever the body says, the rule of another tenant is simply not found.
        call(HttpMethod.PUT, RULES + "/{id}", other.ownerToken(), update(rule, 0), rule.id()).expectStatus()
                .isNotFound();
        Map<String, Object> ownMetric = update(rule, 0);
        ownMetric.put("metricId", other.metricId());
        call(HttpMethod.PUT, RULES + "/{id}", other.ownerToken(), ownMetric, rule.id()).expectStatus().isNotFound();
        call(HttpMethod.PATCH, RULES + "/{id}", other.ownerToken(), Map.of("enabled", false), rule.id())
                .expectStatus().isNotFound();
        call(HttpMethod.GET, "/api/v1/alert-events/{id}", other.ownerToken(), null, eventId).expectStatus()
                .isNotFound();
        call(HttpMethod.GET, "/api/v1/alert-events?ruleId=" + rule.id(), other.ownerToken(), null).expectStatus()
                .isOk().expectBody().jsonPath("$.totalItems").isEqualTo(0);
        call(HttpMethod.GET, "/api/v1/alert-events/{id}", owner.ownerToken(), null, eventId).expectStatus().isOk();
        call(HttpMethod.GET, RULES, other.ownerToken(), null).expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);

        // Nothing of the owner's rule changed.
        assertThat(call(HttpMethod.GET, RULES + "/{id}", owner.ownerToken(), null, rule.id())
                .expectBody(AlertRuleResponse.class).returnResult().getResponseBody()).isEqualTo(rule);
    }

    @Test
    void theTargetIsReturnedToTheTenantButNeverWrittenToTheLogs(CapturedOutput output) {
        TenantFixture tenant = newTenant();
        String target = WebhookReceiver.url(hookPath()) + "?token=TOPSECRET-" + UUID.randomUUID();
        Map<String, Object> body = webhookBody(tenant, "with token");
        body.put("target", target);

        AlertRuleResponse created = createRule(tenant.ownerToken(), body);
        call(HttpMethod.GET, RULES + "/{id}", tenant.ownerToken(), null, created.id()).expectStatus().isOk();
        call(HttpMethod.GET, RULES, tenant.ownerToken(), null).expectStatus().isOk();
        Map<String, Object> rejected = webhookBody(tenant, "bad token");
        rejected.put("target", "https://user:TOPSECRET-pw@hooks.example.com/x?token=TOPSECRET-q");
        postRule(tenant.ownerToken(), rejected).expectStatus().isBadRequest();
        call(HttpMethod.PUT, RULES + "/{id}", tenant.ownerToken(), update(created, 0), created.id())
                .expectStatus().isOk();
        call(HttpMethod.DELETE, RULES + "/{id}", tenant.ownerToken(), null, created.id()).expectStatus()
                .isNoContent();

        assertThat(created.target()).isEqualTo(target);
        assertThat(output.getAll()).doesNotContain("TOPSECRET");
    }

    @Test
    void aMetricOfAnotherTenantIsReportedLikeAMissingOne() {
        TenantFixture owner = newTenant();
        TenantFixture other = newTenant();
        Map<String, Object> foreign = thresholdBody(owner.metricId(), "x", "GT", 1, WebhookReceiver.url(hookPath()));
        Map<String, Object> absent = new HashMap<>(foreign);
        absent.put("metricId", UUID.randomUUID());

        String foreignBody = postRule(other.ownerToken(), foreign).expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody();
        String absentBody = postRule(other.ownerToken(), absent).expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(foreignBody).isEqualTo(absentBody).contains("/metricId").contains("unknown metric");
    }

    @Test
    void aStaleVersionIsAConflictAndEnablingCanSkipTheVersion() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = createRule(tenant.ownerToken(), webhookBody(tenant, "race"));

        call(HttpMethod.PUT, RULES + "/{id}", tenant.ownerToken(), update(rule, 0), rule.id()).expectStatus().isOk();
        String conflict = call(HttpMethod.PUT, RULES + "/{id}", tenant.ownerToken(), update(rule, 0), rule.id())
                .expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody();
        assertThat(conflict).contains("urn:pulseboard:problem:version-conflict");
        call(HttpMethod.PATCH, RULES + "/{id}", tenant.ownerToken(), Map.of("enabled", false, "version", 0),
                rule.id()).expectStatus().isEqualTo(409);
        call(HttpMethod.PUT, RULES + "/{id}", tenant.ownerToken(), withoutVersion(update(rule, 0)), rule.id())
                .expectStatus().isBadRequest();

        AlertRuleResponse disabled = call(HttpMethod.PATCH, RULES + "/{id}", tenant.ownerToken(),
                Map.of("enabled", false), rule.id()).expectStatus().isOk().expectBody(AlertRuleResponse.class)
                .returnResult().getResponseBody();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.version()).isEqualTo(2);
        call(HttpMethod.PATCH, RULES + "/{id}", tenant.ownerToken(), Map.of("version", 2), rule.id())
                .expectStatus().isBadRequest();
        AlertRuleResponse enabled = call(HttpMethod.PATCH, RULES + "/{id}", tenant.ownerToken(),
                Map.of("enabled", true, "version", 2), rule.id()).expectStatus().isOk()
                .expectBody(AlertRuleResponse.class).returnResult().getResponseBody();
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.version()).isEqualTo(3);
    }

    private static Map<String, Object> withoutVersion(Map<String, Object> body) {
        Map<String, Object> copy = new HashMap<>(body);
        copy.remove("version");
        return copy;
    }

    @Test
    void invalidRulesAreRejectedWithTheOffendingField() {
        TenantFixture tenant = newTenant();
        String target = WebhookReceiver.url(hookPath());
        record Case(String field, java.util.function.Consumer<Map<String, Object>> change) {
        }

        List<Case> cases = new ArrayList<>(List.of(
                new Case("/name", body -> body.put("name", "  ")),
                new Case("/name", body -> body.put("name", "n".repeat(101))),
                new Case("/name", body -> body.put("name", "bad\nname")),
                new Case("/name", body -> body.remove("name")),
                new Case("/cooldownSeconds", body -> body.put("cooldownSeconds", 59)),
                new Case("/cooldownSeconds", body -> body.put("cooldownSeconds", 86401)),
                new Case("/cooldownSeconds", body -> body.remove("cooldownSeconds")),
                new Case("/operator", body -> body.remove("operator")),
                new Case("/threshold", body -> body.remove("threshold")),
                new Case("/minSeverity", body -> body.put("minSeverity", "HIGH")),
                new Case("/condition", body -> body.remove("condition")),
                new Case("/channel", body -> body.remove("channel")),
                new Case("/metricId", body -> body.remove("metricId")),
                new Case("/target", body -> body.put("target", "ftp://hooks.example.com/x")),
                new Case("/target", body -> body.put("target", "")),
                new Case("/target", body -> body.put("target", "https://user:pw@hooks.example.com/x")),
                new Case("/target", body -> body.put("target", "https://127.0.0.1/x")),
                new Case("/target", body -> body.put("target", "https://169.254.169.254/latest/meta-data")),
                new Case("/target", body -> body.put("target", "https://[::1]/x")),
                new Case("/target", body -> body.put("target", "https://2130706433/x")),
                new Case("/target", body -> body.put("target", "https://hooks.example.com:6379/x")),
                new Case("/target", body -> body.put("target", "https://" + "a".repeat(2050) + ".example.com/")),
                new Case("/target", body -> body.put("target", "https://no-such-host.invalid/x"))));
        for (Case c : cases) {
            Map<String, Object> body = thresholdBody(tenant.metricId(), "rule", "GT", 1, target);
            c.change().accept(body);
            String problem = postRule(tenant.ownerToken(), body).expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith("application/problem+json")
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(problem).as(c.field() + " " + body.get("target"))
                    .contains("\"field\":\"" + c.field() + "\"")
                    .doesNotContain("pw@").doesNotContain("169.254.169.254");
        }
        Map<String, Object> severityWithThreshold = severityBody(tenant.metricId(), "s", "LOW", target);
        severityWithThreshold.put("threshold", 1);
        assertThat(postRule(tenant.ownerToken(), severityWithThreshold).expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody()).contains("/threshold");
        Map<String, Object> severityWithOperator = severityBody(tenant.metricId(), "s", "LOW", target);
        severityWithOperator.put("operator", "GT");
        assertThat(postRule(tenant.ownerToken(), severityWithOperator).expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody()).contains("/operator");
        Map<String, Object> severityMissing = severityBody(tenant.metricId(), "s", "LOW", target);
        severityMissing.remove("minSeverity");
        assertThat(postRule(tenant.ownerToken(), severityMissing).expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody()).contains("/minSeverity");
        // A body that is not JSON for the model at all.
        client.post().uri(RULES).header(HttpHeaders.AUTHORIZATION, bearer(tenant.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"threshold\": \"NaN\"}".getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_rule WHERE tenant_id = ?", Integer.class,
                tenant.tenantId())).isZero();
    }

    @Test
    void aRuleNameCannotBeUsedTwiceInATenant() {
        TenantFixture tenant = newTenant();
        TenantFixture other = newTenant();
        createRule(tenant.ownerToken(), webhookBody(tenant, "same"));

        postRule(tenant.ownerToken(), webhookBody(tenant, "same")).expectStatus().isEqualTo(409);
        createRule(other.ownerToken(), webhookBody(other, "same"));
    }

    @Test
    void aTenantCannotExceedTheRuleLimit() {
        TenantFixture tenant = newTenant();
        TenantFixture other = newTenant();
        int limit = alertProperties.maxRulesPerTenant();
        for (int i = 0; i < limit; i++) {
            Map<String, Object> body = thresholdBody(tenant.metricId(), "rule " + i, "GT", i, "ops@example.com");
            body.put("channel", "EMAIL_LOG");
            createRule(tenant.ownerToken(), body);
        }

        Map<String, Object> over = thresholdBody(tenant.metricId(), "one too many", "GT", 1, "ops@example.com");
        over.put("channel", "EMAIL_LOG");
        String problem = postRule(tenant.ownerToken(), over).expectStatus().isEqualTo(409)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(problem).contains("urn:pulseboard:problem:alert-rule-limit").contains(Integer.toString(limit));
        // The limit is per tenant, and deleting a rule makes room again.
        createRule(other.ownerToken(),
                thresholdBody(other.metricId(), "mine", "GT", 1, WebhookReceiver.url(hookPath())));
        UUID first = jdbc.queryForObject("SELECT id FROM alert_rule WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
                UUID.class, tenant.tenantId());
        call(HttpMethod.DELETE, RULES + "/{id}", tenant.ownerToken(), null, first).expectStatus().isNoContent();
        createRule(tenant.ownerToken(), over);
    }

    @Test
    void concurrentCreationsStayWithinTheLimit() throws Exception {
        TenantFixture tenant = newTenant();
        int limit = alertProperties.maxRulesPerTenant();
        for (int i = 0; i < limit - 1; i++) {
            jdbc.update("""
                    INSERT INTO alert_rule (id, tenant_id, metric_id, name, condition, operator, threshold,
                                            cooldown_seconds, channel, target)
                    VALUES (?, ?, ?, ?, 'THRESHOLD', 'GT', 1, 60, 'EMAIL_LOG', 'x')
                    """, UUID.randomUUID(), tenant.tenantId(), tenant.metricId(), "seed " + i);
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String name = "racer " + i;
                results.add(pool.submit(() -> {
                    Map<String, Object> body = thresholdBody(tenant.metricId(), name, "GT", 1, "ops@example.com");
                    body.put("channel", "EMAIL_LOG");
                    return postRule(tenant.ownerToken(), body).returnResult(String.class).getStatus().value();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (java.util.concurrent.Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
            assertThat(statuses.stream().filter(s -> s == 409)).hasSize(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_rule WHERE tenant_id = ?", Integer.class,
                tenant.tenantId())).isEqualTo(limit);
    }

    @Test
    void theEventHistoryIsPagedNewestFirstAndFiltered() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse first = createRule(tenant.ownerToken(), webhookBody(tenant, "first"));
        AlertRuleResponse second = createRule(tenant.ownerToken(), webhookBody(tenant, "second"));
        java.time.Instant base = java.time.Instant.parse("2026-02-01T10:00:00Z");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            // Two events share a trigger time, so the id decides their order: the larger one comes first.
            UUID id = i == 3 ? UUID.fromString("00000000-0000-0000-0000-000000000003")
                    : i == 4 ? UUID.fromString("00000000-0000-0000-0000-000000000004") : UUID.randomUUID();
            ids.add(id);
            java.time.Instant at = base.plusSeconds(i == 4 ? 3 : i);
            jdbc.update("""
                    INSERT INTO alert_event (id, tenant_id, rule_id, triggered_at, payload, delivery_status,
                                             next_attempt_at, source_ref, delivered_at)
                    VALUES (?, ?, ?, ?, '{"n": 1}'::jsonb, ?, ?, ?, CASE WHEN ?::text = 'SENT' THEN ?::timestamptz END)
                    """, id, tenant.tenantId(), i % 2 == 0 ? first.id() : second.id(),
                    java.sql.Timestamp.from(at), i < 3 ? "SENT" : "PENDING", java.sql.Timestamp.from(at),
                    "ref-" + i, i < 3 ? "SENT" : "PENDING", java.sql.Timestamp.from(at));
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            String body = call(HttpMethod.GET, "/api/v1/alert-events?size=3&page=" + page, tenant.ownerToken(), null)
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
            seen.addAll(com.jayway.jsonpath.JsonPath.read(body, "$.items[*].sourceRef"));
            if (page == 0) {
                assertThat((Integer) com.jayway.jsonpath.JsonPath.read(body, "$.totalItems")).isEqualTo(7);
                assertThat((Integer) com.jayway.jsonpath.JsonPath.read(body, "$.totalPages")).isEqualTo(3);
            }
        }
        List<String> expected = List.of("ref-6", "ref-5", "ref-4", "ref-3", "ref-2", "ref-1", "ref-0");
        assertThat(seen).containsExactlyElementsOf(expected);

        call(HttpMethod.GET, "/api/v1/alert-events?status=SENT", tenant.ownerToken(), null)
                .expectBody().jsonPath("$.totalItems").isEqualTo(3);
        call(HttpMethod.GET, "/api/v1/alert-events?ruleId=" + first.id(), tenant.ownerToken(), null)
                .expectBody().jsonPath("$.totalItems").isEqualTo(4);
        call(HttpMethod.GET, "/api/v1/alert-events?from=2026-02-01T10:00:02Z&to=2026-02-01T10:00:05Z",
                tenant.ownerToken(), null).expectBody().jsonPath("$.totalItems").isEqualTo(3);
        call(HttpMethod.GET, "/api/v1/alert-events/{id}", tenant.ownerToken(), null, ids.get(6)).expectStatus().isOk()
                .expectBody().jsonPath("$.nextAttemptAt").isNotEmpty().jsonPath("$.deliveredAt").isEmpty()
                .jsonPath("$.ruleName").isEqualTo("first");
        call(HttpMethod.GET, "/api/v1/alert-events/{id}", tenant.ownerToken(), null, ids.get(0)).expectStatus().isOk()
                .expectBody().jsonPath("$.nextAttemptAt").isEmpty().jsonPath("$.deliveredAt").isNotEmpty();

        for (String bad : List.of("size=101", "size=0", "page=-1", "from=2026-02-02T00:00:00Z&to=2026-02-01T00:00:00Z",
                "status=DONE", "ruleId=not-a-uuid", "from=yesterday")) {
            call(HttpMethod.GET, "/api/v1/alert-events?" + bad, tenant.ownerToken(), null).expectStatus()
                    .isBadRequest();
        }
        call(HttpMethod.GET, "/api/v1/alert-events?size=100", tenant.ownerToken(), null).expectStatus().isOk();
    }
}
