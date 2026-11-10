package io.pulseboard.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

/** Plain HTTP helpers for the stream ticket tests: issuing tickets and opening streams with them. */
public final class StreamTicketTestSupport {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StreamTicketTestSupport() {
    }

    /** POST /api/v1/stream/ticket with the given headers (name/value pairs). */
    public static HttpResponse<String> requestTicket(int port, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/stream/ticket")).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot request a ticket", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", ex);
        }
    }

    /** Issues a ticket for the access token and returns its value; fails unless the answer is 200. */
    public static String issueTicket(int port, String accessToken) {
        HttpResponse<String> response = requestTicket(port, "Authorization", "Bearer " + accessToken);
        assertThat(response.statusCode()).as("ticket status, body: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("ticket").asString();
    }

    /** GET /api/v1/stream/anomalies with the raw query string and headers (name/value pairs). */
    public static SseTestClient openStream(int port, String query, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/stream/anomalies" + (query == null ? "" : "?" + query)))
                .header("Accept", "text/event-stream");
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return SseTestClient.open(request.GET().build());
    }

    /** GET of a path with its query string; returns the status. */
    public static int statusOfGet(int port, String pathAndQuery) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(30)).GET().build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException ex) {
            throw new IllegalStateException("GET " + pathAndQuery + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", ex);
        }
    }

    public static SseTestClient openWithTicket(int port, String ticket) {
        return openStream(port, "ticket=" + ticket);
    }

    /** Hex SHA-256, the Redis key suffix of a ticket. */
    public static String sha256Hex(String ticket) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
