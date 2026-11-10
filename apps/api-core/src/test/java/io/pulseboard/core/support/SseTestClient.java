package io.pulseboard.core.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.awaitility.Awaitility;

/**
 * A real server-sent events client for tests: it keeps the HTTP connection open on its own thread and records the
 * comments and events it receives. Closing it drops the TCP connection, like a browser tab being closed.
 */
public final class SseTestClient implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** One dispatched event. */
    public record Event(String name, String id, String data) {
    }

    private final int status;
    private final HttpResponse<InputStream> response;
    private final String errorBody;
    private final List<String> comments = new CopyOnWriteArrayList<>();
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean ended = new AtomicBoolean();
    private volatile Instant endedAt;
    private Thread reader;

    private SseTestClient(HttpResponse<InputStream> response, String errorBody) {
        this.response = response;
        this.status = response.statusCode();
        this.errorBody = errorBody;
    }

    /** Connects and waits for the response headers; a 200 answer starts the reader thread. */
    public static SseTestClient open(int port, String accessToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/stream/anomalies")).header("Accept", "text/event-stream");
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return open(request.GET().build());
    }

    public static SseTestClient open(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream body = response.body()) {
                    return new SseTestClient(response, new String(body.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            SseTestClient client = new SseTestClient(response, null);
            client.reader = Thread.ofVirtual().name("sse-test-reader").start(client::readLoop);
            return client;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot open the stream", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while opening the stream", ex);
        }
    }

    public int status() {
        return status;
    }

    public String errorBody() {
        return errorBody;
    }

    public String header(String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public List<String> comments() {
        return List.copyOf(comments);
    }

    /** True once the server (or {@link #close()}) ended the stream. */
    public boolean hasEnded() {
        return ended.get();
    }

    /** When the read loop saw the end of the stream, or null while it is still open. */
    public Instant endedAt() {
        return endedAt;
    }

    /** Waits until an event matching the predicate arrived and returns it. */
    public Event awaitEvent(Predicate<Event> predicate, Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(25))
                .until(() -> events.stream().anyMatch(predicate));
        return events.stream().filter(predicate).findFirst().orElseThrow();
    }

    public void awaitComment(String text, Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(25))
                .until(() -> comments.contains(text));
    }

    public void awaitEnd(Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(25)).untilTrue(ended);
    }

    private void readLoop() {
        String name = null;
        String id = null;
        StringBuilder data = new StringBuilder();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        events.add(new Event(name, id, data.toString()));
                    }
                    name = null;
                    id = null;
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    comments.add(line.substring(1).strip());
                } else if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).strip();
                } else if (line.startsWith("id:")) {
                    id = line.substring("id:".length()).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).strip());
                }
            }
        } catch (IOException ex) {
            // The connection was closed locally or by the server.
        } finally {
            endedAt = Instant.now();
            ended.set(true);
        }
    }

    /** Drops the connection. */
    @Override
    public void close() {
        if (status == 200) {
            try {
                response.body().close();
            } catch (IOException ex) {
                // already closed
            }
        }
        if (reader != null) {
            reader.interrupt();
        }
        ended.set(true);
    }
}
