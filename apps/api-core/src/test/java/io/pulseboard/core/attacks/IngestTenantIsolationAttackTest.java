package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IngestPayloads.Point;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Cross-tenant writes through ingestion: foreign metric ids must look exactly like unknown ones (body and timing),
 * an upsert must never overwrite another tenant's sample, and concurrent batches on the same samples must not
 * fail.
 */
class IngestTenantIsolationAttackTest extends IngestQueryAttackSupport {

    private static final int TIMING_SAMPLES = 25;

    @Test
    void foreignAndUnknownMetricIdsGetTheSameAnswer() {
        Tenant victim = tenant("AVG");
        Tenant attacker = tenant("AVG");
        Instant base = monthStart();

        HttpResponse<String> mixed = ingestRaw(attacker.apiKey(), IngestPayloads.batch(List.of(
                Point.of(victim.metricId(), base, 1),
                Point.of(UUID.randomUUID(), base, 1),
                Point.of(attacker.metricId(), base, 1))));
        assertThat(mixed.statusCode()).isEqualTo(202);
        assertThat(mixed.body()).isEqualTo("{\"accepted\":1,\"rejected\":[{\"index\":0,\"reason\":\"unknown metric\"},"
                + "{\"index\":1,\"reason\":\"unknown metric\"}]}");

        HttpResponse<String> foreign = ingestRaw(attacker.apiKey(), IngestPayloads.single(victim.metricId(), base, 1));
        HttpResponse<String> unknown = ingestRaw(attacker.apiKey(),
                IngestPayloads.single(UUID.randomUUID(), base, 1));
        assertThat(foreign.body()).isEqualTo(unknown.body());
        assertThat(foreign.headers().map().keySet()).isEqualTo(unknown.headers().map().keySet());
        assertThat(storedRows(victim.metricId())).isZero();
    }

    @Test
    void foreignAndUnknownMetricIdsTakeTheSameTime() {
        Tenant victim = tenant("AVG");
        Tenant attacker = tenant("AVG");
        setPlan(attacker, "PRO");
        Instant base = monthStart();
        String foreign = IngestPayloads.single(victim.metricId(), base, 1);
        String unknown = IngestPayloads.single(UUID.randomUUID(), base, 1);
        medianMillis(5, () -> ingestRaw(attacker.apiKey(), foreign));

        double foreignMs = medianMillis(TIMING_SAMPLES, () -> ingestRaw(attacker.apiKey(), foreign));
        double unknownMs = medianMillis(TIMING_SAMPLES, () -> ingestRaw(attacker.apiKey(), unknown));
        System.out.printf(Locale.ROOT, "ingest timing: foreign %.2f ms, unknown %.2f ms%n", foreignMs, unknownMs);

        // Same statement, same 0-row outcome: no measurable gap beyond local jitter.
        assertThat(Math.abs(foreignMs - unknownMs)).isLessThan(Math.max(5.0, unknownMs));
    }

    @Test
    void upsertNeverOverwritesAnotherTenantsSample() {
        Tenant victim = tenant("AVG");
        Tenant attacker = tenant("AVG");
        Instant ts = monthStart().plusSeconds(42);
        assertThat(ingestRaw(victim.apiKey(), IngestPayloads.single(victim.metricId(), ts, 1)).statusCode())
                .isEqualTo(202);

        List<String> spellings = List.of(victim.metricId().toString(),
                victim.metricId().toString().toUpperCase(Locale.ROOT));
        for (String id : spellings) {
            HttpResponse<String> response = ingestRaw(attacker.apiKey(), "{\"points\":[{\"metricId\":\"" + id
                    + "\",\"ts\":\"" + ts + "\",\"value\":999}]}");
            assertThat(response.body()).contains("unknown metric").contains("\"accepted\":0");
        }
        // A batch mixing the attacker's own metric with the victim's at the same timestamp.
        ingestRaw(attacker.apiKey(), IngestPayloads.batch(List.of(Point.of(attacker.metricId(), ts, 5),
                Point.of(victim.metricId(), ts, 999))));

        assertThat(storedValue(victim.metricId(), ts)).isEqualTo("1");
        assertThat(storedRows(victim.metricId())).isEqualTo(1);
        assertThat(storedValue(attacker.metricId(), ts)).isEqualTo("5");
    }

    @Test
    void concurrentBatchesInTheSameOrderOnTheSameSamplesAllSucceed() throws Exception {
        Tenant tenant = tenant("SUM");
        setPlan(tenant, "PRO");
        String ascending = IngestPayloads.batch(points(tenant.metricId(), false));
        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            tasks.add(() -> ingestRaw(tenant.apiKey(), ascending));
        }
        assertThat(parallel(tasks)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(202));
        assertThat(storedRows(tenant.metricId())).isEqualTo(1000);
    }

    @Test
    void concurrentBatchesInOppositeOrderOnTheSameSamplesAllSucceed() throws Exception {
        Tenant tenant = tenant("SUM");
        setPlan(tenant, "PRO");
        String ascending = IngestPayloads.batch(points(tenant.metricId(), false));
        String descending = IngestPayloads.batch(points(tenant.metricId(), true));
        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            String body = i % 2 == 0 ? ascending : descending;
            tasks.add(() -> ingestRaw(tenant.apiKey(), body));
        }

        List<HttpResponse<String>> results = parallel(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(202));
        assertThat(storedRows(tenant.metricId())).isEqualTo(1000);
    }

    private static List<Point> points(UUID metricId, boolean reversed) {
        Instant base = monthStart().plusSeconds(86_400);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            points.add(Point.of(metricId, base.plusSeconds(i), i));
        }
        if (reversed) {
            Collections.reverse(points);
        }
        return points;
    }
}
