package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IngestPayloads.Point;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * Hostile ingestion bodies: size limits with and without Content-Length, compressed bodies, content types,
 * number literals, identifier and timestamp tricks, deep nesting, duplicate keys and the 1000-point cap.
 */
class IngestPayloadAttackTest extends IngestQueryAttackSupport {

    @Value("${pulseboard.ingest.max-body-bytes}")
    private int maxBodyBytes;

    private Tenant tenant;
    private Instant base;

    @BeforeEach
    void setUp() {
        tenant = tenant("MAX");
        base = monthStart();
    }

    @Test
    void bodyAtTheLimitIsReadAndOneByteMoreIsRejectedWithOrWithoutContentLength() {
        byte[] atLimit = padded(maxBodyBytes);
        byte[] overLimit = padded(maxBodyBytes + 1);

        for (boolean chunked : List.of(false, true)) {
            HttpResponse<String> ok = sendBytes(INGEST, atLimit, chunked, API_KEY_HEADER, tenant.apiKey(),
                    "Content-Type", JSON);
            assertThat(ok.statusCode()).as("exactly %d bytes, chunked=%s", maxBodyBytes, chunked).isEqualTo(202);

            HttpResponse<String> tooLarge = sendBytes(INGEST, overLimit, chunked, API_KEY_HEADER, tenant.apiKey(),
                    "Content-Type", JSON);
            assertThat(tooLarge.statusCode()).as("one byte over, chunked=%s", chunked).isEqualTo(413);
            assertThat(tooLarge.body()).contains("urn:pulseboard:problem:payload-too-large");
        }
    }

    @Test
    void compressedBodiesAreNeverInflated() throws IOException {
        // 20 MB of whitespace around a valid batch compresses to a few KB: a decompression bomb if inflated.
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("{\"points\":[".getBytes(StandardCharsets.UTF_8));
            byte[] spaces = " ".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 20; i++) {
                gzip.write(spaces);
            }
            gzip.write(("{\"metricId\":\"" + tenant.metricId() + "\",\"ts\":\"" + base + "\",\"value\":1}]}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertThat(compressed.size()).isLessThan(maxBodyBytes);

        HttpResponse<String> response = sendBytes(INGEST, compressed.toByteArray(), false, API_KEY_HEADER,
                tenant.apiKey(), "Content-Type", JSON, "Content-Encoding", "gzip");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("not valid JSON");
        assertThat(storedRows(tenant.metricId())).isZero();
    }

    @Test
    void onlyJsonContentTypesReachTheParser() {
        String body = point(tenant.metricId(), base.toString(), "1");
        for (String type : List.of("text/plain", "application/x-www-form-urlencoded", "application/vnd.api+json",
                "application/xml", "multipart/form-data; boundary=x")) {
            assertThat(ingestRaw(tenant.apiKey(), body, "Content-Type", type).statusCode()).as(type).isEqualTo(415);
        }
        assertThat(ingestRaw(tenant.apiKey(), body, "Content-Type", "APPLICATION/JSON").statusCode()).isEqualTo(202);
        // A UTF-16 body is decoded by the JSON parser's encoding detection, so it is the same batch.
        byte[] utf16 = point(tenant.metricId(), base.plusSeconds(1).toString(), "2")
                .getBytes(StandardCharsets.UTF_16BE);
        assertThat(sendBytes(INGEST, utf16, false, API_KEY_HEADER, tenant.apiKey(), "Content-Type",
                "application/json; charset=UTF-16").statusCode()).isEqualTo(202);
        assertThat(storedValue(tenant.metricId(), base.plusSeconds(1))).isEqualTo("2");
    }

    @Test
    void numberLiteralsAreEitherStoredExactlyOrRejected() {
        record Case(String literal, int status, String stored, String reason) {
        }

        List<Case> cases = List.of(
                // Negative zero loses its sign (read as BigDecimal): stored as 0, which is numerically equal.
                new Case("-0.0", 202, "0", null),
                new Case("1E+2", 202, "100", null),
                new Case("4.9e-324", 202, "5e-324", null),
                // Finite but beyond the 1e100 magnitude bound: rejected per point (aggregates must stay finite).
                new Case("1.7976931348623157e308", 202, null, "value magnitude must not exceed 1e100"),
                new Case("1e100", 202, "1e+100", null),
                // Below the smallest subnormal: rounds to zero (documented below the findings as a note).
                new Case("1e-400", 202, "0", null),
                new Case("1e400", 202, null, "value must be a finite number"),
                new Case("-1e400", 202, null, "value must be a finite number"),
                new Case("1" + "0".repeat(999), 202, null, "value must be a finite number"),
                new Case("1" + "0".repeat(1000), 400, null, null),
                new Case("NaN", 400, null, null),
                new Case("Infinity", 400, null, null),
                new Case("-Infinity", 400, null, null),
                new Case("+1", 400, null, null),
                new Case(".5", 400, null, null),
                new Case("0x10", 400, null, null),
                new Case("01", 400, null, null),
                new Case("1.", 400, null, null),
                new Case("\"1\"", 400, null, null),
                new Case("true", 400, null, null),
                new Case("null", 400, null, null),
                new Case("[1]", 400, null, null),
                new Case("{\"$numberDouble\":\"1\"}", 400, null, null));
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            Instant ts = base.plusSeconds(i);
            String label = c.literal().length() > 24 ? c.literal().substring(0, 24) + "..." : c.literal();
            HttpResponse<String> response = ingestRaw(tenant.apiKey(), point(tenant.metricId(), ts.toString(),
                    c.literal()));
            assertThat(response.statusCode()).as(label).isEqualTo(c.status());
            if (c.reason() != null) {
                assertThat(response.body()).as(label).contains(c.reason());
            }
            assertThat(storedValue(tenant.metricId(), ts)).as(label).isEqualTo(c.stored());
            assertNoInternals(response);
        }
    }

    @Test
    void identifierAndTimestampTricksNeverReachStorageUnchecked() {
        UUID metric = tenant.metricId();
        String id = metric.toString();
        for (String badId : List.of("١" + id.substring(1), "０" + id.substring(1), "{" + id + "}",
                id.replace("-", ""), "1-1-1-1-1", id + " ", " " + id, id + "\\u0000")) {
            String body = "{\"points\":[{\"metricId\":\"" + badId + "\",\"ts\":\"" + base + "\",\"value\":1}]}";
            assertThat(status(body)).as("metricId %s", badId).isEqualTo(400);
        }
        assertThat(ingestRaw(tenant.apiKey(), point(new UUID(0, 0), base.toString(), "1")).body())
                .contains("unknown metric");

        // Upper-case id is the same metric: stored once, no duplicate series.
        ingestRaw(tenant.apiKey(), "{\"points\":[{\"metricId\":\"" + id.toUpperCase(Locale.ROOT) + "\",\"ts\":\""
                + base.plusSeconds(1) + "\",\"value\":7}]}");
        assertThat(storedValue(metric, base.plusSeconds(1))).isEqualTo("7");

        Instant storageEnd = YearMonth.now(ZoneOffset.UTC).plusMonths(4).atDay(1).atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        assertThat(rejection(storageEnd.minus(1, ChronoUnit.MICROS).toString())).isNull();
        assertThat(rejection(storageEnd.toString())).isEqualTo("timestamp is outside the range accepted for storage");
        assertThat(rejection("0000-01-01T00:00:00Z")).isEqualTo("timestamp is outside the range accepted for storage");
        assertThat(rejection("9999-12-31T23:59:59.999999Z"))
                .isEqualTo("timestamp is outside the range accepted for storage");
        assertThat(rejection("2026-11-10T10:00:00+18:30")).isEqualTo("timestamp cannot be represented");
        assertThat(rejection("2026-11-10T10:00:00-23:59")).isEqualTo("timestamp cannot be represented");
        assertThat(rejection(base.plusSeconds(2).toString().toLowerCase(Locale.ROOT))).isNull();

        for (String badTs : List.of(base.toString().replace("T", " "), "2026-11-10T10:00:00", "2026-02-30T10:00:00Z",
                "2026-12-31T23:59:60Z", "+12026-11-10T10:00:00Z", "٢026-11-10T10:00:00Z", "2026-W45-2T10:00:00Z",
                "1731232800", "")) {
            String body = "{\"points\":[{\"metricId\":\"" + id + "\",\"ts\":\"" + badTs + "\",\"value\":1}]}";
            assertThat(status(body)).as("ts %s", badTs).isEqualTo(400);
        }
        // Sub-microsecond precision is truncated before the range check and before storage.
        Instant nanos = base.plusSeconds(3).plusNanos(999);
        assertThat(rejection(nanos.toString())).isNull();
        assertThat(storedValue(metric, base.plusSeconds(3))).isEqualTo("1");
    }

    @Test
    void structuralAbuseIsRejectedAsAWholeWithoutLeakingInternals() {
        String valid = "{\"metricId\":\"" + tenant.metricId() + "\",\"ts\":\"" + base + "\",\"value\":1}";
        List<String> bodies = List.of(
                "[".repeat(5000) + "]".repeat(5000),
                "{\"points\":[{\"metricId\":\"" + tenant.metricId() + "\",\"ts\":\"" + base + "\",\"value\":"
                        + "[".repeat(3000) + "]".repeat(3000) + "}]}",
                "{\"points\":[" + valid.replace("\"value\":1", "\"value\":1,\"value\":2") + "]}",
                "{\"points\":[],\"points\":[" + valid + "]}",
                "{\"points\":[" + valid + "]}{\"points\":[" + valid + "]}",
                "{\"points\":[" + valid + "]} x",
                "/*c*/{\"points\":[" + valid + "]}",
                "{'points':[]}",
                "{\"points\":[" + valid + "],\"tenantId\":\"" + UUID.randomUUID() + "\"}",
                "{\"points\":[" + valid.replace("}", ",\"tenantId\":\"" + UUID.randomUUID() + "\"}") + "]}",
                "{\"points\":[]}",
                "{\"points\":{}}",
                "{\"points\":null}",
                "{\"points\":[" + valid + "],\"source\":\"\"}",
                "{\"points\":[" + valid + "],\"source\":\"" + "s".repeat(129) + "\"}",
                "{}", "[]", "null", "\"points\"", "", " ");
        for (String body : bodies) {
            HttpResponse<String> response = ingestRaw(tenant.apiKey(), body);
            String label = body.length() > 40 ? body.substring(0, 40) + "..." : body;
            assertThat(response.statusCode()).as(label).isEqualTo(400);
            assertThat(response.body()).as(label).contains("urn:pulseboard:problem:validation");
            assertNoInternals(response);
        }
        assertThat(storedRows(tenant.metricId())).isZero();
    }

    @Test
    void equivalentJsonSpellingsAreTheSameBatch() {
        String valid = "{\"metricId\":\"" + tenant.metricId() + "\",\"ts\":\"" + base + "\",\"value\":3}";
        assertThat(ingestRaw(tenant.apiKey(), "{\"p\\u006fints\":[" + valid + "]}").statusCode()).isEqualTo(202);
        assertThat(ingestRaw(tenant.apiKey(), "﻿{\"points\":[" + valid + "]}").statusCode()).isEqualTo(202);
        assertThat(ingestRaw(tenant.apiKey(), "{\"points\":[" + valid + "],\"source\":\""
                + "😀".repeat(128) + "\"}").statusCode()).isEqualTo(202);
        assertThat(storedRows(tenant.metricId())).isEqualTo(1);
    }

    @Test
    void thousandPointsAreAcceptedAndOneMoreRejectsTheBatch() {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            points.add(Point.of(tenant.metricId(), base.plusSeconds(i), i));
        }
        HttpResponse<String> over = ingestRaw(tenant.apiKey(), IngestPayloads.batch(points));
        assertThat(over.statusCode()).isEqualTo(400);
        assertThat(over.body()).contains("/points");
        assertThat(storedRows(tenant.metricId())).isZero();

        HttpResponse<String> full = ingestRaw(tenant.apiKey(), IngestPayloads.batch(points.subList(0, 1000)));
        assertThat(full.statusCode()).isEqualTo(202);
        assertThat(full.body()).contains("\"accepted\":1000");
    }

    @Test
    void aThousandInvalidPointsGiveABoundedErrorList() {
        StringBuilder body = new StringBuilder("{\"points\":[");
        for (int i = 0; i < 1000; i++) {
            body.append(i == 0 ? "" : ",").append("{\"metricId\":1,\"ts\":2,\"value\":\"x\",\"extra\":[1]}");
        }
        long started = System.nanoTime();
        HttpResponse<String> response = ingestRaw(tenant.apiKey(), body.append("]}").toString());
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body().split("\"field\"", -1)).hasSizeLessThanOrEqualTo(21);
        assertThat(millis).as("validation time").isLessThan(2000);
    }

    @Test
    void duplicatePointsInOneBatchAreLastWriteWins() {
        HttpResponse<String> response = ingestRaw(tenant.apiKey(), IngestPayloads.batch(List.of(
                Point.of(tenant.metricId(), base, 1), Point.of(tenant.metricId(), base, 2))));
        assertThat(response.statusCode()).isEqualTo(202);
        // The earlier duplicate is reported as superseded, so "accepted" counts stored rows only.
        assertThat(response.body()).contains("\"accepted\":1")
                .contains("{\"index\":0,\"reason\":\"superseded by a later point with the same metricId and ts\"}");
        assertThat(storedRows(tenant.metricId())).isEqualTo(1);
        assertThat(storedValue(tenant.metricId(), base)).isEqualTo("2");
    }

    private int status(String body) {
        HttpResponse<String> response = ingestRaw(tenant.apiKey(), body);
        assertNoInternals(response);
        return response.statusCode();
    }

    /** Ingests one point at {@code ts} and returns the rejection reason, or {@code null} when it was stored. */
    private String rejection(String ts) {
        HttpResponse<String> response = ingestRaw(tenant.apiKey(), point(tenant.metricId(), ts, "1"));
        assertThat(response.statusCode()).as(ts).isEqualTo(202);
        if (response.body().contains("\"accepted\":1")) {
            return null;
        }
        String body = response.body();
        int start = body.indexOf("\"reason\":\"") + "\"reason\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private byte[] padded(int size) {
        String head = "{\"points\":[{\"metricId\":\"" + tenant.metricId() + "\",\"ts\":\"" + base
                + "\",\"value\":1}]";
        String tail = "}";
        int padding = size - head.length() - tail.length();
        return (head + " ".repeat(padding) + tail).getBytes(StandardCharsets.UTF_8);
    }
}
