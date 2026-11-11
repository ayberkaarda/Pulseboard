package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.webhook.HostResolver;
import io.pulseboard.core.application.alert.webhook.WebhookPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Resolves host names with the platform resolver. A lookup cannot be interrupted, so it runs on a small bounded
 * pool of daemon threads and the caller gives up after the connect timeout: a name server that never answers
 * cannot hold a delivery worker (when all lookup threads are stuck, further lookups fail at once and are retried
 * later).
 */
@Component
public class SystemHostResolver implements HostResolver {

    private static final int MAX_LOOKUPS = 8;

    private final long timeoutMillis;
    private final ThreadPoolExecutor lookups;

    public SystemHostResolver(WebhookPolicy policy) {
        this.timeoutMillis = policy.connectTimeout().toMillis();
        AtomicInteger counter = new AtomicInteger();
        this.lookups = new ThreadPoolExecutor(0, MAX_LOOKUPS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "alert-dns-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        Future<List<InetAddress>> lookup;
        try {
            lookup = lookups.submit(() -> List.of(InetAddress.getAllByName(host)));
        } catch (RejectedExecutionException ex) {
            throw new UnknownHostException("resolver busy");
        }
        try {
            return lookup.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            lookup.cancel(true);
            throw new UnknownHostException("lookup timed out");
        } catch (InterruptedException ex) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnknownHostException("lookup interrupted");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof UnknownHostException unknown) {
                throw unknown;
            }
            throw new UnknownHostException("lookup failed");
        }
    }
}
