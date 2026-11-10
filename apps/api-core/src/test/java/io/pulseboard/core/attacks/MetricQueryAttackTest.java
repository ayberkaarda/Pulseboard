package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IngestPayloads.Point;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/v1/metrics/{id}/points} under attack: range and step abuse, the 2000-bucket edge, SQL injection
 * in every parameter, cross-tenant reads (IDOR) incl. response and timing equality, path variants, error leaks,
 * and aggregation correctness on a known and on an extreme series.
 */
class MetricQueryAttackTest extends IngestQueryAttackSupport {

    private static final Pattern VALUE = Pattern.compile("\"value\":(\"?[^,}]+\"?)");

    private Tenant tenant;
    private Instant base;
    private String token;

    @BeforeEach
    void setUp() {
        tenant = tenant("MAX");
        base = monthStart();
        token = tenant.owner().accessToken();
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            points.add(Point.of(tenant.metricId(), base.plusSeconds(i * 60L), i));
        }
        assertThat(ingestRaw(tenant.apiKey(), IngestPayloads.batch(points)).statusCode()).isEqualTo(202);
    }

    @Test
    void knownSeriesGivesTheExactAggregates() {
        // 1..20 in one bucket: AVG 10.5, SUM 210, MAX 20, P95 (continuous) 1 + 0.95 * 19 = 19.05.
        record Expected(String aggregation, double value) {
        }

        for (Expected expected : List.of(new Expected("AVG", 10.5), new Expected("SUM", 210),
                new Expected("MAX", 20), new Expected("P95", 19.05))) {
            UUID metric = addMetric(tenant, "known_" + expected.aggregation().toLowerCase(Locale.ROOT),
                    expected.aggregation());
            List<Point> points = new ArrayList<>();
            for (int v = 20; v >= 1; v--) {
                points.add(Point.of(metric, base.plusSeconds(v), v));
            }
            ingestRaw(tenant.apiKey(), IngestPayloads.batch(points));

            HttpResponse<String> response = query(token, metric, range(base, base.plusSeconds(60), "PT1M"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"count\":20");
            assertThat(Double.parseDouble(value(response))).as(expected.aggregation())
                    .isCloseTo(expected.value(), within(1e-9));
        }
    }

    @Test
    void rangeAndStepAbuseIsRejectedWithAProblem() {
        Instant hour = base.plusSeconds(3600);
        record Case(String query, String field) {
        }

        List<Case> cases = List.of(
                new Case(range(hour, base, null), "to"),
                new Case(range(base, base, null), "to"),
                new Case(range(base, base.plus(Duration.ofDays(367)), null), "to"),
                new Case("?from=1969-12-31T23:59:59Z&to=1970-01-01T00:00:01Z", "from"),
                new Case("?from=" + base + "&to=%2B10000-01-01T00:00:00Z", "from"),
                new Case(range(base, hour, "PT0S"), "step"),
                new Case(range(base, hour, "P0D"), "step"),
                new Case(range(base, hour, "PT-5M"), "step"),
                new Case(range(base, hour, "-PT5M"), "step"),
                new Case(range(base, hour, "PT1.5S"), "step"),
                new Case(range(base, hour, "PT0.000001S"), "step"),
                new Case(range(base, hour, "P367D"), "step"),
                new Case(range(base, hour, "PT9223372036854775807S"), "step"),
                new Case(range(base, hour, "PT99999999999999999999S"), "step"),
                new Case(range(base, hour, "5"), "step"),
                new Case(range(base, hour, "5m"), "step"),
                new Case(range(base, hour, "P1W"), "step"),
                new Case(range(base, hour, "P1M"), "step"),
                new Case(range(base, hour, "PT1M") + "&step=PT1S", "step"),
                new Case(range(base, hour, "PT1M%27%20OR%201%3D1--"), "step"),
                new Case(range(base, base.plusSeconds(2001), "PT1S"), "step"));
        for (Case c : cases) {
            HttpResponse<String> response = query(token, tenant.metricId(), c.query());
            assertThat(response.statusCode()).as(c.query()).isEqualTo(400);
            assertThat(response.body()).as(c.query()).contains("urn:pulseboard:problem:validation")
                    .contains("\"field\":\"" + c.field() + "\"");
            assertNoInternals(response);
        }
        // Not RFC 3339 at all: rejected by parameter conversion, still a 400 problem.
        for (String query : List.of("?from=2026-11-10&to=2026-11-11", "?from=1731232800&to=1731236400",
                "?from=" + base + "%27%3B%20DROP%20TABLE%20metric_point--&to=" + hour,
                "?from=2026-11-10T10:00:00+01:00&to=2026-11-10T12:00:00Z", "?from=" + base, "", "?from=&to=")) {
            HttpResponse<String> response = query(token, tenant.metricId(), query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(header(response, "Content-Type")).isEqualTo(PROBLEM_JSON);
        }
        // The data survived every attempt.
        assertThat(storedRows(tenant.metricId())).isEqualTo(60);
    }

    @Test
    void bucketLimitEdgesAndAcceptedVariants() {
        assertThat(stepOf(query(token, tenant.metricId(), range(base, base.plusSeconds(2000), "PT1S"))))
                .isEqualTo("PT1S");
        HttpResponse<String> overLimit = query(token, tenant.metricId(), range(base, base.plusSeconds(2001), "PT1S"));
        assertThat(overLimit.body()).contains("use at least PT2S");
        // A range not aligned to the step touches one more bucket: 2000 s from .5 s spans 2001 buckets.
        assertThat(query(token, tenant.metricId(), range(base.plusMillis(500), base.plusSeconds(2000).plusMillis(500),
                "PT1S")).statusCode()).isEqualTo(400);
        assertThat(stepOf(query(token, tenant.metricId(), range(base, base.plusSeconds(2000), null))))
                .isEqualTo("PT1S");
        assertThat(stepOf(query(token, tenant.metricId(), range(base, base.plusSeconds(2001), null))))
                .isEqualTo("PT5S");
        assertThat(stepOf(query(token, tenant.metricId(), range(base, base.plus(Duration.ofDays(366)), null))))
                .isEqualTo("PT6H");
        assertThat(stepOf(query(token, tenant.metricId(), range(base, base.plusSeconds(3600), "pt5m"))))
                .isEqualTo("PT5M");
        assertThat(stepOf(query(token, tenant.metricId(), range(base, base.plusSeconds(3600), "%20PT5M%20"))))
                .isEqualTo("PT5M");
        assertThat(stepOf(query(token, tenant.metricId(), range(base, base.plusSeconds(3600), "P366D"))))
                .isEqualTo("PT8784H");
        // Sub-microsecond range: valid, empty.
        HttpResponse<String> tiny = query(token, tenant.metricId(),
                "?from=" + base.plusNanos(1) + "&to=" + base.plusNanos(2));
        assertThat(tiny.statusCode()).isEqualTo(200);
        assertThat(tiny.body()).contains("\"points\":[]");
        // An offset given as %2B is honoured: 01:00+01:00 is 00:00Z.
        HttpResponse<String> offset = query(token, tenant.metricId(), "?from="
                + base.atOffset(ZoneOffset.ofHours(1)).toString().replace("+", "%2B")
                + "&to=" + base.plusSeconds(120) + "&step=PT1M");
        assertThat(offset.body()).contains("\"from\":\"" + base + "\"").contains("\"count\":1");
    }

    @Test
    void sqlInjectionInThePathIsRejectedBeforeAnyQuery() {
        for (String id : List.of("1'%20OR%20'1'='1", "x%27%3B%20DROP%20TABLE%20t--",
                tenant.metricId() + "%27--", "00000000-0000-0000-0000-00000000000g")) {
            HttpResponse<String> response = getWithBearer("/api/v1/metrics/" + id + "/points"
                    + range(base, base.plusSeconds(60), null), token);
            assertThat(response.statusCode()).as(id).isEqualTo(400);
            assertNoInternals(response);
        }
        assertThat(storedRows(tenant.metricId())).isEqualTo(60);
    }

    @Test
    void anotherTenantsMetricLooksExactlyLikeAnUnknownOne() {
        Tenant attacker = tenant("MAX");
        String q = range(base, base.plusSeconds(3600), "PT1M");
        UUID unknownId = UUID.randomUUID();

        HttpResponse<String> foreign = query(attacker.owner().accessToken(), tenant.metricId(), q);
        HttpResponse<String> unknown = query(attacker.owner().accessToken(), unknownId, q);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(foreign.body().replace(tenant.metricId().toString(), "<id>"))
                .isEqualTo(unknown.body().replace(unknownId.toString(), "<id>"));
        assertThat(foreign.headers().map().keySet()).isEqualTo(unknown.headers().map().keySet());

        // Timing: the lookup is one indexed query by (id, tenant) either way.
        medianMillis(5, () -> query(attacker.owner().accessToken(), tenant.metricId(), q));
        double foreignMs = medianMillis(25, () -> query(attacker.owner().accessToken(), tenant.metricId(), q));
        double unknownMs = medianMillis(25, () -> query(attacker.owner().accessToken(), UUID.randomUUID(), q));
        System.out.printf(Locale.ROOT, "query timing: foreign %.2f ms, unknown %.2f ms%n", foreignMs, unknownMs);
        assertThat(Math.abs(foreignMs - unknownMs)).isLessThan(Math.max(5.0, unknownMs));
    }

    @Test
    void pathVariantsNeverReachTheHandlerUnauthenticatedOrLeakInternals() {
        String id = tenant.metricId().toString();
        String q = range(base, base.plusSeconds(60), null);
        assertThat(raw("GET", "/api/v1/metrics/" + id + "/points" + q, null).statusCode()).isEqualTo(401);
        assertThat(raw("GET", "/api/v1/%6Detrics/" + id + "/points" + q, null).statusCode()).isEqualTo(401);
        for (String path : List.of("/api/v1/metrics/" + id + "/points/", "/api/v1/METRICS/" + id + "/points",
                "/api/v1/metrics/" + id + "%2Fpoints", "/api/v1/metrics/x/../" + id + "/points",
                "/api/v1/metrics/" + id + ";x=1/points", "/api/v1/metrics//" + id + "/points",
                "/api/v1/metrics/" + id + "/points.json")) {
            HttpResponse<String> response = getWithBearer(path + q, token);
            assertThat(response.statusCode()).as(path).isIn(400, 404);
            assertNoInternals(response);
            assertThat(response.body()).as(path).doesNotContain("\"points\":[").doesNotContainPattern("Tomcat/\\d");
        }
    }

    @Test
    void extremeButAcceptedSamplesDoNotBreakAvgAndSumQueries() {
        // Fix for downsample-float-overflow-fails-query: ingestion bounds |value| at 1e100 (per point), so a
        // stored series can no longer overflow avg()/sum(); values beyond the bound are rejected, not stored.
        UUID avg = addMetric(tenant, "extreme_avg", "AVG");
        UUID sum = addMetric(tenant, "extreme_sum", "SUM");
        HttpResponse<String> ingest = ingestRaw(tenant.apiKey(), IngestPayloads.batch(List.of(
                new Point(avg, base.toString(), "1e155"), new Point(avg, base.plusSeconds(1).toString(), "0"),
                new Point(avg, base.plusSeconds(2).toString(), "1e100"),
                new Point(sum, base.toString(), "1.7976931348623157e308"),
                new Point(sum, base.plusSeconds(1).toString(), "1e100"),
                new Point(sum, base.plusSeconds(2).toString(), "-1e100"),
                new Point(sum, base.plusSeconds(3).toString(), "1e100"))));
        assertThat(ingest.statusCode()).isEqualTo(202);
        assertThat(ingest.body()).contains("\"accepted\":5")
                .contains("{\"index\":0,\"reason\":\"value magnitude must not exceed 1e100\"}")
                .contains("{\"index\":3,\"reason\":\"value magnitude must not exceed 1e100\"}");

        HttpResponse<String> avgResponse = query(token, avg, range(base, base.plusSeconds(60), "PT1M"));
        assertThat(avgResponse.statusCode()).isEqualTo(200);
        assertThat(Double.parseDouble(value(avgResponse))).isEqualTo(5e99);

        HttpResponse<String> sumResponse = query(token, sum, range(base, base.plusSeconds(60), "PT1M"));
        assertThat(sumResponse.statusCode()).isEqualTo(200);
        assertThat(Double.parseDouble(value(sumResponse))).isEqualTo(1e100);
    }

    @Test
    void extremeSampleQueryFailureLeaksNoInternals() {
        // Whatever the outcome of the finding above, the error body stays generic.
        UUID avg = addMetric(tenant, "pinned_avg", "AVG");
        ingestRaw(tenant.apiKey(), IngestPayloads.batch(List.of(
                new Point(avg, base.toString(), "1e155"), new Point(avg, base.plusSeconds(1).toString(), "0"))));

        HttpResponse<String> response = query(token, avg, range(base, base.plusSeconds(60), "PT1M"));

        assertThat(response.statusCode()).isIn(200, 409);
        assertNoInternals(response);
    }

    @Test
    void p95OfExtremeSamplesIsStillAJsonNumber() {
        // Fix for p95-interpolation-overflow-returns-string: 1e308 / -1e308 are rejected at ingestion; the most
        // extreme storable pair interpolates to a finite number.
        UUID p95 = addMetric(tenant, "extreme_p95", "P95");
        HttpResponse<String> ingest = ingestRaw(tenant.apiKey(), IngestPayloads.batch(List.of(
                new Point(p95, base.toString(), "1e308"), new Point(p95, base.plusSeconds(1).toString(), "-1e308"),
                new Point(p95, base.plusSeconds(2).toString(), "1e100"),
                new Point(p95, base.plusSeconds(3).toString(), "-1e100"))));
        assertThat(ingest.body()).contains("\"accepted\":2");

        HttpResponse<String> response = query(token, p95, range(base, base.plusSeconds(60), "PT1M"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(value(response)).doesNotStartWith("\"");
        assertThat(Double.parseDouble(value(response))).isCloseTo(9e99, within(1e85));
    }

    private static String value(HttpResponse<String> response) {
        Matcher matcher = VALUE.matcher(response.body());
        assertThat(matcher.find()).as(response.body()).isTrue();
        return matcher.group(1);
    }

    private static String stepOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Matcher matcher = Pattern.compile("\"step\":\"([^\"]+)\"").matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
