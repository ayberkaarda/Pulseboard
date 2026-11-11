package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.webhook.ResolvedWebhookTarget;
import io.pulseboard.core.application.alert.webhook.WebhookPolicy;
import io.pulseboard.core.application.alert.webhook.WebhookSender;
import io.pulseboard.core.application.alert.webhook.WebhookTransportException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * Sends webhook requests with Apache HttpClient.
 *
 * <p>Every request gets its own client whose DNS resolver can only answer with the addresses that were validated
 * for the target, so the connection goes to a pinned address while the URL host stays the TLS server name and the
 * {@code Host} header (certificate validation therefore still applies to the name). Redirects, retries, cookies,
 * content compression and proxies are off.
 *
 * <p>The response body is never read: as soon as the status line and the headers are in, the exchange is aborted
 * and the connection is closed hard, so a receiver cannot keep a worker busy by dripping an endless body. The
 * whole call, TLS handshake and header wait included, is capped by a wall-clock watchdog that aborts the exchange
 * (per-read timeouts alone do not bound a receiver that sends one byte at a time). This is why Spring's
 * {@code RestClient} is not used: it drains the body when the response is closed.
 */
@Component
public class ApacheWebhookSender implements WebhookSender {

    static final String USER_AGENT = "Pulseboard-Alerts";

    /** Runs the watchdogs; a daemon thread shared by all calls, the work per call is a few close operations. */
    private static final ScheduledExecutorService WATCHDOG = newWatchdog();

    private final WebhookPolicy policy;

    public ApacheWebhookSender(WebhookPolicy policy) {
        this.policy = policy;
    }

    private static ScheduledExecutorService newWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "alert-webhook-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override
    public int post(ResolvedWebhookTarget target, Map<String, String> headers, byte[] body)
            throws WebhookTransportException {
        Timeout connectTimeout = Timeout.ofMilliseconds(policy.connectTimeout().toMillis());
        Timeout readTimeout = Timeout.ofMilliseconds(policy.readTimeout().toMillis());
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedDnsResolver(target.target().host(), target.addresses()))
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connectTimeout)
                        .setSocketTimeout(readTimeout)
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(connectTimeout).build())
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setUserAgent(USER_AGENT)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setResponseTimeout(readTimeout)
                        .setConnectionRequestTimeout(connectTimeout)
                        .build())
                .build();
        HttpPost request = new HttpPost(target.target().uri());
        headers.forEach(request::setHeader);
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            expired.set(true);
            abortQuietly(request);
            connectionManager.close(CloseMode.IMMEDIATE);
        }, policy.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
        ClassicHttpResponse response = null;
        try {
            response = client.executeOpen(null, request, null);
            return response.getCode();
        } catch (InterruptedIOException ex) {
            throw new WebhookTransportException("timeout");
        } catch (SSLException ex) {
            throw new WebhookTransportException(expired.get() ? "timeout" : "TLS error");
        } catch (UnknownHostException ex) {
            throw new WebhookTransportException("host cannot be resolved");
        } catch (IOException ex) {
            throw new WebhookTransportException(expired.get() ? "timeout" : "network error: " + name(ex));
        } catch (RuntimeException ex) {
            throw new WebhookTransportException(expired.get() ? "timeout" : "request error: " + name(ex));
        } finally {
            watchdog.cancel(false);
            // Hard close, never a graceful one: a graceful close of a response drains what is left of the body.
            abortQuietly(request);
            if (response != null) {
                try {
                    if (response instanceof ModalCloseable closeable) {
                        closeable.close(CloseMode.IMMEDIATE);
                    } else {
                        response.close();
                    }
                } catch (RuntimeException | IOException ex) {
                    // the connection manager is shut down below anyway
                }
            }
            client.close(CloseMode.IMMEDIATE);
            connectionManager.close(CloseMode.IMMEDIATE);
        }
    }

    private static void abortQuietly(HttpPost request) {
        try {
            request.abort();
        } catch (RuntimeException ex) {
            // best effort; the connection manager is closed as well
        }
    }

    private static String name(Exception ex) {
        return ex.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", "");
    }

    /** Answers for the one validated host name and nothing else; it never asks DNS. */
    private static final class PinnedDnsResolver implements DnsResolver {

        private final String host;
        private final InetAddress[] addresses;

        PinnedDnsResolver(String host, List<InetAddress> addresses) {
            this.host = canonical(host);
            this.addresses = addresses.toArray(InetAddress[]::new);
        }

        @Override
        public InetAddress[] resolve(String requested) throws UnknownHostException {
            if (requested == null || !host.equals(canonical(requested))) {
                throw new UnknownHostException(requested);
            }
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String requested) {
            return requested;
        }

        /** Case-insensitive and without the root label's trailing dot. */
        private static String canonical(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
        }
    }
}
