package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.webhook.ResolvedWebhookTarget;
import io.pulseboard.core.application.alert.webhook.WebhookPolicy;
import io.pulseboard.core.application.alert.webhook.WebhookSender;
import io.pulseboard.core.application.alert.webhook.WebhookTransportException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * Sends webhook requests with Apache HttpClient.
 *
 * <p>Every request gets its own client whose DNS resolver can only answer with the addresses that were validated
 * for the target, so the connection goes to a pinned address while the URL host stays the TLS server name and the
 * {@code Host} header (certificate validation therefore still applies to the name). Redirects, retries, cookies,
 * content compression and proxies are off. The response body is never read: once the status line is in, the
 * connection is closed at once. This is why Spring's {@code RestClient} is not used: it drains the body when the
 * response is closed.
 */
@Component
public class ApacheWebhookSender implements WebhookSender {

    static final String USER_AGENT = "Pulseboard-Alerts";

    private final WebhookPolicy policy;

    public ApacheWebhookSender(WebhookPolicy policy) {
        this.policy = policy;
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
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
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
                .build()) {
            HttpPost request = new HttpPost(target.target().uri());
            headers.forEach(request::setHeader);
            request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
            ClassicHttpResponse response = client.executeOpen(null, request, null);
            int status = response.getCode();
            // Closes the connection without draining the body.
            response.close();
            return status;
        } catch (InterruptedIOException ex) {
            throw new WebhookTransportException("timeout");
        } catch (SSLException ex) {
            throw new WebhookTransportException("TLS error");
        } catch (UnknownHostException ex) {
            throw new WebhookTransportException("host cannot be resolved");
        } catch (IOException ex) {
            throw new WebhookTransportException("network error: " + name(ex));
        } catch (RuntimeException ex) {
            throw new WebhookTransportException("request error: " + name(ex));
        } finally {
            connectionManager.close(CloseMode.IMMEDIATE);
        }
    }

    private static String name(Exception ex) {
        return ex.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", "");
    }

    /** Answers for the one validated host name and nothing else; it never asks DNS. */
    private static final class PinnedDnsResolver implements DnsResolver {

        private final String host;
        private final InetAddress[] addresses;

        PinnedDnsResolver(String host, java.util.List<InetAddress> addresses) {
            this.host = host;
            this.addresses = addresses.toArray(InetAddress[]::new);
        }

        @Override
        public InetAddress[] resolve(String requested) throws UnknownHostException {
            if (!host.equalsIgnoreCase(requested)) {
                throw new UnknownHostException(requested);
            }
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String requested) {
            return requested;
        }
    }
}
