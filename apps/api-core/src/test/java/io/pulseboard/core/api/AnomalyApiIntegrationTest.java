package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.AnomalyIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Listing, reading and acknowledging anomalies: tenant isolation, filters, paging order and roles. */
class AnomalyApiIntegrationTest extends AnomalyIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    private ResponseSpec get(String token, String uri, Object... variables) {
        RestTestClient.RequestHeadersSpec<?> request = client.get().uri(uri, variables);
        return (token == null ? request : request.header(HttpHeaders.AUTHORIZATION, bearer(token))).exchange();
    }

    private ResponseSpec ack(String token, UUID id) {
        RestTestClient.RequestHeadersSpec<?> request = client.post().uri("/api/v1/anomalies/{id}/ack", id);
        return (token == null ? request : request.header(HttpHeaders.AUTHORIZATION, bearer(token))).exchange();
    }

    private JsonNode body(ResponseSpec response) {
        return JSON.readTree(response.expectBody(String.class).returnResult().getResponseBody());
    }

    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").stringValue()));
        return ids;
    }

    @Test
    void listIsScopedToTheTenantOfTheToken() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        StoredAnomaly ofA = insertAnomaly(a, T0, "HIGH");
        StoredAnomaly ofB = insertAnomaly(b, T0, "HIGH");

        assertThat(ids(body(get(a.ownerToken(), "/api/v1/anomalies").expectStatus().isOk())))
                .containsExactly(ofA.id().toString());
        assertThat(ids(body(get(b.ownerToken(), "/api/v1/anomalies").expectStatus().isOk())))
                .containsExactly(ofB.id().toString());
        // Filtering by the other tenant's metric or service reveals nothing either.
        assertThat(ids(body(get(a.ownerToken(), "/api/v1/anomalies?metricId={m}", b.metricId())
                .expectStatus().isOk()))).isEmpty();
        assertThat(ids(body(get(a.ownerToken(), "/api/v1/anomalies?serviceId={s}", b.serviceId())
                .expectStatus().isOk()))).isEmpty();
    }

    @Test
    void anAnomalyRepresentationHasTheDocumentedFields() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, T0, "MEDIUM");

        get(tenant.ownerToken(), "/api/v1/anomalies/{id}", stored.id())
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo(stored.id().toString())
                .jsonPath("$.tenantId").isEqualTo(tenant.tenantId().toString())
                .jsonPath("$.metricId").isEqualTo(tenant.metricId().toString())
                .jsonPath("$.serviceId").isEqualTo(tenant.serviceId().toString())
                .jsonPath("$.metricName").isEqualTo("latency_p95_ms")
                .jsonPath("$.serviceName").isEqualTo("checkout")
                .jsonPath("$.windowStart").isEqualTo("2026-03-01T00:00:00Z")
                .jsonPath("$.windowEnd").isEqualTo("2026-03-01T00:05:00Z")
                .jsonPath("$.score").isEqualTo(0.9)
                .jsonPath("$.detector").isEqualTo("ZSCORE")
                .jsonPath("$.severity").isEqualTo("MEDIUM")
                .jsonPath("$.details.z").isEqualTo(4.2)
                .jsonPath("$.createdAt").exists()
                .jsonPath("$.acknowledgedBy").isEmpty()
                .jsonPath("$.acknowledgedAt").isEmpty();
    }

    @Test
    void filtersCombineAndEachOneNarrowsTheResult() {
        TenantFixture tenant = newTenant();
        UUID otherMetric = newMetric(tenant, "billing", "queue_depth");
        UUID otherService = serviceOf(otherMetric);
        StoredAnomaly lowOld = insertAnomaly(tenant, T0, "LOW");
        StoredAnomaly highMid = insertAnomaly(tenant, T0.plusSeconds(3600), "HIGH");
        StoredAnomaly mediumNew = insertAnomaly(tenant, T0.plusSeconds(7200), "MEDIUM");
        StoredAnomaly otherHigh = insertAnomaly(tenant.tenantId(), otherMetric, T0.plusSeconds(1800), "HIGH");
        jdbc.update("UPDATE anomaly SET acknowledged_at = now() WHERE id = ?", highMid.id());
        String token = tenant.ownerToken();

        assertThat(ids(body(get(token, "/api/v1/anomalies").expectStatus().isOk()))).containsExactly(
                mediumNew.id().toString(), highMid.id().toString(), otherHigh.id().toString(),
                lowOld.id().toString());
        assertThat(ids(body(get(token, "/api/v1/anomalies?severity=HIGH").expectStatus().isOk())))
                .containsExactly(highMid.id().toString(), otherHigh.id().toString());
        assertThat(ids(body(get(token, "/api/v1/anomalies?metricId={m}", otherMetric).expectStatus().isOk())))
                .containsExactly(otherHigh.id().toString());
        assertThat(ids(body(get(token, "/api/v1/anomalies?serviceId={s}", otherService).expectStatus().isOk())))
                .containsExactly(otherHigh.id().toString());
        assertThat(ids(body(get(token, "/api/v1/anomalies?serviceId={s}", tenant.serviceId())
                .expectStatus().isOk()))).containsExactly(mediumNew.id().toString(), highMid.id().toString(),
                lowOld.id().toString());
        assertThat(ids(body(get(token, "/api/v1/anomalies?acknowledged=true").expectStatus().isOk())))
                .containsExactly(highMid.id().toString());
        assertThat(ids(body(get(token, "/api/v1/anomalies?acknowledged=false").expectStatus().isOk())))
                .containsExactly(mediumNew.id().toString(), otherHigh.id().toString(), lowOld.id().toString());
        // from is inclusive, to is exclusive, both on the window start.
        assertThat(ids(body(get(token, "/api/v1/anomalies?from={f}&to={t}", T0.plusSeconds(1800),
                T0.plusSeconds(7200)).expectStatus().isOk())))
                .containsExactly(highMid.id().toString(), otherHigh.id().toString());
        assertThat(ids(body(get(token, "/api/v1/anomalies?severity=HIGH&acknowledged=false&from={f}",
                T0.plusSeconds(60)).expectStatus().isOk()))).containsExactly(otherHigh.id().toString());
        assertThat(ids(body(get(token, "/api/v1/anomalies?from={f}", "2026-03-01T03:00:00+03:00")
                .expectStatus().isOk()))).hasSize(4);
    }

    @Test
    void pagesAreStableAndDoNotOverlapEvenWithEqualWindowStarts() {
        TenantFixture tenant = newTenant();
        List<UUID> metrics = new ArrayList<>(List.of(tenant.metricId()));
        for (int i = 0; i < 4; i++) {
            metrics.add(newMetric(tenant, "svc" + i, "metric_" + i));
        }
        List<StoredAnomaly> all = new ArrayList<>();
        // 5 metrics x 2 windows; the five rows of one window share the same window start.
        for (int window = 0; window < 2; window++) {
            for (UUID metric : metrics) {
                all.add(insertAnomaly(tenant.tenantId(), metric, T0.plusSeconds(600L * window), "LOW"));
            }
        }
        List<String> expected = all.stream()
                .sorted(Comparator.comparing(StoredAnomaly::windowStart)
                        .thenComparing(a -> a.id().toString()).reversed())
                .map(a -> a.id().toString()).toList();

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            JsonNode result = body(get(tenant.ownerToken(), "/api/v1/anomalies?size=3&page={p}", page)
                    .expectStatus().isOk());
            assertThat(result.get("page").intValue()).isEqualTo(page);
            assertThat(result.get("size").intValue()).isEqualTo(3);
            assertThat(result.get("totalItems").longValue()).isEqualTo(10);
            assertThat(result.get("totalPages").longValue()).isEqualTo(4);
            paged.addAll(ids(result));
        }

        assertThat(paged).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(ids(body(get(tenant.ownerToken(), "/api/v1/anomalies?size=3&page=4").expectStatus().isOk())))
                .isEmpty();
        // Asking again returns the same order.
        assertThat(ids(body(get(tenant.ownerToken(), "/api/v1/anomalies?size=3&page=1").expectStatus().isOk())))
                .containsExactlyElementsOf(expected.subList(3, 6));
    }

    @Test
    void defaultPageSizeIsTwentyAndTheMaximumIsOneHundred() {
        TenantFixture tenant = newTenant();
        for (int i = 0; i < 25; i++) {
            insertAnomaly(tenant, T0.plusSeconds(i), "LOW");
        }

        JsonNode defaults = body(get(tenant.ownerToken(), "/api/v1/anomalies").expectStatus().isOk());
        assertThat(defaults.get("items")).hasSize(20);
        assertThat(defaults.get("size").intValue()).isEqualTo(20);
        assertThat(defaults.get("totalItems").longValue()).isEqualTo(25);
        assertThat(body(get(tenant.ownerToken(), "/api/v1/anomalies?size=100").expectStatus().isOk())
                .get("items")).hasSize(25);
        get(tenant.ownerToken(), "/api/v1/anomalies?size=101").expectStatus().isBadRequest();
    }

    @Test
    void invalidParametersAreRejectedWithProblemDetails() {
        TenantFixture tenant = newTenant();
        String token = tenant.ownerToken();
        String[] bad = {
            "size=0", "size=-1", "size=101", "page=-1", "page=100001", "size=abc", "page=1.5",
            "severity=CRITICAL", "severity=high", "acknowledged=maybe", "metricId=not-a-uuid",
            "serviceId=1234", "from=yesterday", "to=2026-13-45T00:00:00Z", "from=2026-03-01T00:00:00Z"
                    + "&to=2026-03-01T00:00:00Z", "from=2026-03-02T00:00:00Z&to=2026-03-01T00:00:00Z",
        };
        for (String query : bad) {
            get(token, "/api/v1/anomalies?" + query)
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
        // Injection attempts are just values that fail to parse; they never reach SQL.
        get(token, "/api/v1/anomalies?severity=HIGH'%20OR%20'1'='1").expectStatus().isBadRequest();
        get(token, "/api/v1/anomalies?metricId=%27%3B%20DROP%20TABLE%20anomaly%3B--").expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly", Integer.class)).isNotNull();
    }

    @Test
    void getByIdOfAnotherTenantIsNotFoundLikeAnUnknownId() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        StoredAnomaly ofA = insertAnomaly(a, T0, "HIGH");

        String foreign = get(b.ownerToken(), "/api/v1/anomalies/{id}", ofA.id())
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        String unknown = get(b.ownerToken(), "/api/v1/anomalies/{id}", UUID.randomUUID())
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(JSON.readTree(foreign).get("type")).isEqualTo(JSON.readTree(unknown).get("type"));
        assertThat(foreign).doesNotContain(a.tenantId().toString());
        get(b.ownerToken(), "/api/v1/anomalies/not-a-uuid").expectStatus().isBadRequest();
    }

    @Test
    void acknowledgingRecordsTheUserOnceAndRepeatsAreHarmless() {
        TenantFixture tenant = newTenant();
        TokenResponse admin = signInAs(tenant, "admin@example.com", "ADMIN");
        StoredAnomaly stored = insertAnomaly(tenant, T0, "HIGH");

        JsonNode first = body(ack(admin.accessToken(), stored.id()).expectStatus().isOk());
        assertThat(first.get("acknowledgedBy").stringValue()).isEqualTo(admin.userId().toString());
        assertThat(first.get("acknowledgedAt").stringValue()).isNotBlank();

        // A second acknowledgement, by the same or another user, changes nothing and still answers 200.
        JsonNode again = body(ack(admin.accessToken(), stored.id()).expectStatus().isOk());
        JsonNode byOwner = body(ack(tenant.ownerToken(), stored.id()).expectStatus().isOk());
        assertThat(again).isEqualTo(first);
        assertThat(byOwner).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT acknowledged_by FROM anomaly WHERE id = ?", UUID.class, stored.id()))
                .isEqualTo(admin.userId());
        // The state is visible through the read endpoints, and the open filter no longer returns it.
        assertThat(body(get(tenant.ownerToken(), "/api/v1/anomalies/{id}", stored.id())))
                .isEqualTo(first);
        assertThat(ids(body(get(tenant.ownerToken(), "/api/v1/anomalies?acknowledged=false").expectStatus()
                .isOk()))).isEmpty();
    }

    @Test
    void concurrentAcknowledgementsRecordExactlyOneUser() throws Exception {
        TenantFixture tenant = newTenant();
        List<TokenResponse> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            users.add(signInAs(tenant, "admin" + i + "@example.com", "ADMIN"));
        }
        StoredAnomaly stored = insertAnomaly(tenant, T0, "HIGH");

        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (TokenResponse user : users) {
                results.add(pool.submit(() -> body(ack(user.accessToken(), stored.id()).expectStatus().isOk())));
            }
            JsonNode reference = results.getFirst().get();
            for (Future<JsonNode> result : results) {
                assertThat(result.get()).isEqualTo(reference);
            }
        }
        UUID recorded = jdbc.queryForObject("SELECT acknowledged_by FROM anomaly WHERE id = ?", UUID.class,
                stored.id());
        assertThat(users).extracting(TokenResponse::userId).contains(recorded);
    }

    @Test
    void viewersCanReadButNotAcknowledge() {
        TenantFixture tenant = newTenant();
        TokenResponse viewer = signInAs(tenant, "viewer@example.com", "VIEWER");
        StoredAnomaly stored = insertAnomaly(tenant, T0, "HIGH");

        get(viewer.accessToken(), "/api/v1/anomalies").expectStatus().isOk();
        get(viewer.accessToken(), "/api/v1/anomalies/{id}", stored.id()).expectStatus().isOk();
        ack(viewer.accessToken(), stored.id())
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(jdbc.queryForObject("SELECT acknowledged_at IS NULL FROM anomaly WHERE id = ?", Boolean.class,
                stored.id())).isTrue();
    }

    @Test
    void acknowledgingAnotherTenantsAnomalyIs404AndChangesNothing() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        StoredAnomaly ofA = insertAnomaly(a, T0, "HIGH");

        ack(b.ownerToken(), ofA.id()).expectStatus().isNotFound();
        ack(b.ownerToken(), UUID.randomUUID()).expectStatus().isNotFound();

        assertThat(jdbc.queryForObject("SELECT acknowledged_at IS NULL AND acknowledged_by IS NULL FROM anomaly "
                + "WHERE id = ?", Boolean.class, ofA.id())).isTrue();
    }

    @Test
    void unauthenticatedAndApiKeyCallersAreRefused() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, T0, "HIGH");
        String apiKey = issueApiKey(tenant.ownerToken(), "agent").key();

        get(null, "/api/v1/anomalies").expectStatus().isUnauthorized();
        get(null, "/api/v1/anomalies/{id}", stored.id()).expectStatus().isUnauthorized();
        ack(null, stored.id()).expectStatus().isUnauthorized();
        for (String path : new String[] {"/api/v1/anomalies", "/api/v1/anomalies/" + stored.id()}) {
            client.get().uri(path).header(API_KEY_HEADER, apiKey).exchange().expectStatus().isForbidden();
        }
        client.post().uri("/api/v1/anomalies/{id}/ack", stored.id()).header(API_KEY_HEADER, apiKey).exchange()
                .expectStatus().isForbidden();
        get("garbage.token.value", "/api/v1/anomalies").expectStatus().isUnauthorized();
    }
}
