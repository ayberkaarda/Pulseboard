package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Helpers for the ingestion ({@code POST /api/v1/ingest}) and query ({@code GET /api/v1/metrics/{id}/points})
 * attacks: tenants with a metric and an API key, raw byte bodies with or without a Content-Length, parallel
 * bursts, and direct reads of what was stored.
 */
abstract class IngestQueryAttackSupport extends AttackTestSupport {

    protected static final String INGEST = "/api/v1/ingest";

    /** Fragments that must never appear in an error body: stack traces, SQL, driver or framework internals. */
    private static final List<String> INTERNALS = List.of("Exception", "PSQL", "SQL [", "SELECT ", "INSERT ",
            "metric_point", "metric_definition", "org.springframework", "org.postgresql", "java.", "\tat ");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Value("${local.server.port}")
    private int serverPort;

    private final HttpClient bytesClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** A fresh tenant with one service, one metric of the given aggregation and one API key. */
    protected Tenant tenant(String aggregation) {
        TokenResponse owner = registerTenant();
        UUID serviceId = createService(owner.accessToken(), "checkout").id();
        UUID metricId = createMetric(owner.accessToken(), serviceId, "latency", aggregation).id();
        return new Tenant(owner, serviceId, metricId, issueApiKey(owner.accessToken(), "agent").key());
    }

    protected UUID addMetric(Tenant tenant, String name, String aggregation) {
        return createMetric(tenant.owner().accessToken(), tenant.serviceId(), name, aggregation).id();
    }

    protected void setPlan(Tenant tenant, String plan) {
        jdbcTemplate.update("UPDATE tenant SET plan = ? WHERE id = ?", plan, tenant.owner().tenantId());
    }

    /** Start of the current UTC month: always inside a provisioned partition. */
    protected static Instant monthStart() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    protected HttpResponse<String> ingestRaw(String apiKey, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = API_KEY_HEADER;
        all[1] = apiKey;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return raw("POST", INGEST, body, all);
    }

    protected HttpResponse<String> query(String accessToken, UUID metricId, String queryString) {
        return getWithBearer("/api/v1/metrics/" + metricId + "/points" + queryString, accessToken);
    }

    protected static String range(Instant from, Instant to, String step) {
        return "?from=" + from + "&to=" + to + (step == null ? "" : "&step=" + step);
    }

    /** Single point body with the value written verbatim. */
    protected static String point(UUID metricId, String ts, String valueLiteral) {
        return "{\"points\":[{\"metricId\":\"" + metricId + "\",\"ts\":\"" + ts + "\",\"value\":" + valueLiteral
                + "}]}";
    }

    /**
     * Sends raw bytes. With {@code chunked} the body is streamed without a Content-Length
     * ({@code Transfer-Encoding: chunked}), so only the server's own read limit can stop it.
     */
    protected HttpResponse<String> sendBytes(String path, byte[] body, boolean chunked, String... headers) {
        HttpRequest.BodyPublisher fixed = HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.BodyPublisher publisher = chunked ? new UnknownLength(fixed) : fixed;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
                .timeout(Duration.ofSeconds(30))
                .POST(publisher);
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            return bytesClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("Request failed: POST " + path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: POST " + path, ex);
        }
    }

    /** Runs all tasks at once on virtual threads and returns their results in submission order. */
    protected static <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(task));
            }
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    /** The stored value as PostgreSQL prints it ({@code -0}, {@code 1e+155}), or {@code null} when absent. */
    protected String storedValue(UUID metricId, Instant ts) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT value::text FROM metric_point WHERE metric_id = ? AND ts = ?", String.class, metricId,
                OffsetDateTime.ofInstant(ts, ZoneOffset.UTC));
        return values.isEmpty() ? null : values.getFirst();
    }

    protected int storedRows(UUID metricId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM metric_point WHERE metric_id = ?",
                Integer.class, metricId);
        return count == null ? 0 : count;
    }

    protected static void assertNoInternals(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        for (String fragment : INTERNALS) {
            assertThat(body).as("error body of %s %s", response.request().method(), response.uri())
                    .doesNotContain(fragment);
        }
    }

    /** Median of {@code samples} timings of {@code request}, in milliseconds. */
    protected static double medianMillis(int samples, Runnable request) {
        double[] millis = new double[samples];
        for (int i = 0; i < samples; i++) {
            long started = System.nanoTime();
            request.run();
            millis[i] = (System.nanoTime() - started) / 1e6;
        }
        Arrays.sort(millis);
        return millis[samples / 2];
    }

    protected record Tenant(TokenResponse owner, UUID serviceId, UUID metricId, String apiKey) {
    }

    /** Hides the length of a publisher, which makes the client send the body chunked. */
    private record UnknownLength(HttpRequest.BodyPublisher delegate) implements HttpRequest.BodyPublisher {

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(subscriber);
        }
    }
}
