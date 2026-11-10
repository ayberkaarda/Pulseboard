package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.domain.metrics.MetricSampleRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.AnomalyIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;

class ServiceLastPointIntegrationTest extends AnomalyIntegrationTest {

    private static final ParameterizedTypeReference<List<ServiceResponse>> SERVICE_LIST =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private MetricSampleRepository sampleRepository;

    @Test
    void lastPointAtIsNullWithoutSamplesAndTheNewestSampleOverAllMetricsOtherwise() {
        TenantFixture tenant = newTenant();
        UUID second = newMetric(tenant, "billing", "queue_depth");
        UUID third = createMetric(tenant.ownerToken(), tenant.serviceId(), "error_rate", "AVG").id();
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        assertThat(rawBody(tenant)).contains("\"lastPointAt\":null");
        assertThat(services(tenant)).extracting(ServiceResponse::lastPointAt).containsOnlyNulls();

        insertPoint(tenant.metricId(), base.minusSeconds(600));
        insertPoint(third, base.minusSeconds(30));
        insertPoint(tenant.metricId(), base.minusSeconds(3600));
        insertPoint(second, base.minusSeconds(120));

        Map<String, Instant> byName = new HashMap<>();
        services(tenant).forEach(service -> byName.put(service.name(), service.lastPointAt()));
        assertThat(byName).containsEntry("checkout", base.minusSeconds(30))
                .containsEntry("billing", base.minusSeconds(120));
        ServiceResponse detail = client.get().uri("/api/v1/services/{id}", tenant.serviceId())
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.ownerToken()))
                .exchange().expectStatus().isOk().expectBody(ServiceResponse.class).returnResult().getResponseBody();
        assertThat(detail.lastPointAt()).isEqualTo(base.minusSeconds(30));
    }

    @Test
    void anotherTenantsSamplesNeverShowUpAndForeignServiceIdsAreIgnored() {
        TenantFixture victim = newTenant();
        TenantFixture other = newTenant();
        insertPoint(victim.metricId(), Instant.now().minusSeconds(10));

        assertThat(services(other)).extracting(ServiceResponse::lastPointAt).containsOnlyNulls();
        assertThat(sampleRepository.latestSampleByService(TenantId.of(other.tenantId()), List.of(victim.serviceId())))
                .isEmpty();
        assertThat(sampleRepository.latestSampleByService(TenantId.of(victim.tenantId()), List.of(victim.serviceId())))
                .containsOnlyKeys(victim.serviceId());
    }

    /** 10 000 samples over 400 metrics of 40 services and several monthly partitions: the list stays cheap. */
    @Test
    void theListStaysFastForManyServicesMetricsAndSamples() {
        TenantFixture tenant = newTenant();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<UUID> metrics = new ArrayList<>();
        for (int s = 0; s < 40; s++) {
            UUID serviceId = createService(tenant.ownerToken(), "svc-" + s).id();
            for (int m = 0; m < 10; m++) {
                UUID metricId = UUID.randomUUID();
                jdbc.update("INSERT INTO metric_definition (id, tenant_id, service_id, name, unit, aggregation) "
                        + "VALUES (?, ?, ?, ?, 'ms', 'AVG')", metricId, tenant.tenantId(), serviceId, "m" + m);
                metrics.add(metricId);
            }
        }
        // 25 samples per metric, two days apart, from now to 48 days ahead (across monthly partitions)
        jdbc.update("""
                INSERT INTO metric_point (metric_id, ts, value)
                SELECT m, ?::timestamptz + (g * interval '2 days'), g
                  FROM unnest(?::uuid[]) m, generate_series(0, 24) g
                """, Timestamp.from(now), metrics.toArray(UUID[]::new));
        Integer total = jdbc.queryForObject("SELECT count(*) FROM metric_point WHERE metric_id = ANY (?)",
                Integer.class, (Object) metrics.toArray(UUID[]::new));
        assertThat(total).isEqualTo(10_000);

        long started = System.nanoTime();
        List<ServiceResponse> services = services(tenant);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(services).hasSize(41);
        assertThat(services).filteredOn(service -> service.name().startsWith("svc-"))
                .extracting(ServiceResponse::lastPointAt).containsOnly(now.plus(48, ChronoUnit.DAYS));
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    }

    private void insertPoint(UUID metricId, Instant ts) {
        jdbc.update("INSERT INTO metric_point (metric_id, ts, value) VALUES (?, ?, 1)", metricId, Timestamp.from(ts));
    }

    private List<ServiceResponse> services(TenantFixture tenant) {
        return client.get().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.ownerToken()))
                .exchange().expectStatus().isOk().expectBody(SERVICE_LIST).returnResult().getResponseBody();
    }

    private String rawBody(TenantFixture tenant) {
        return client.get().uri("/api/v1/services")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.ownerToken()))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }
}
