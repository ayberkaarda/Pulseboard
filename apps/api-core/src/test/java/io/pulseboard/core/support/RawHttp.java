package io.pulseboard.core.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal HTTP client that sends the path exactly as given (no normalisation or re-encoding). */
public final class RawHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private RawHttp() {
    }

    /** POSTs a JSON body; {@code headers} are name/value pairs. */
    public static HttpResponse<String> postJson(int port, String path, String body, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("POST " + path + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: POST " + path, ex);
        }
    }

    /** A login body for a tenant that does not exist (the handler answers 401 "Invalid credentials"). */
    public static String unknownLoginBody() {
        return "{\"tenantSlug\":\"no-such-tenant\",\"email\":\"a@example.com\",\"password\":\"irrelevant-pw\"}";
    }
}
