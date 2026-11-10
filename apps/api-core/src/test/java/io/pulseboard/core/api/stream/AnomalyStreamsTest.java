package io.pulseboard.core.api.stream;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.application.anomaly.AnomalyEventSource;
import io.pulseboard.core.application.anomaly.StreamAdmission;
import io.pulseboard.core.application.anomaly.StreamPolicy;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** The close/attach state machine of a stream, driven without Redis or HTTP. */
class AnomalyStreamsTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final int QUEUE_CAPACITY = 256;

    /** Counts subscriptions like the Redis listener registry does: one per open, released by the first close. */
    private static final class CountingSource implements AnomalyEventSource {

        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger subscribed = new AtomicInteger();
        private volatile Consumer<Consumer<String>> beforeReturn = listener -> {
        };

        @Override
        public Subscription subscribe(TenantId tenantId, Consumer<String> listener) {
            subscribed.incrementAndGet();
            active.incrementAndGet();
            AtomicInteger own = new AtomicInteger();
            beforeReturn.accept(listener);
            return () -> {
                closeCalls.incrementAndGet();
                if (own.incrementAndGet() == 1) {
                    active.decrementAndGet();
                }
            };
        }
    }

    private final CountingSource source = new CountingSource();
    private final StreamAdmission admission = new StreamAdmission(
            new StreamPolicy(5, 10, Duration.ofSeconds(30), Duration.ofMinutes(30)));
    private final AnomalyStreams streams = new AnomalyStreams(source, admission, JsonMapper.builder().build());

    /** Stands in for the servlet container's side of an emitter and keeps the callbacks the stream registers. */
    private static final class Callbacks {
        private final AtomicReference<Runnable> completion = new AtomicReference<>();
        private final AtomicReference<Runnable> timeout = new AtomicReference<>();
        private final AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        private final AtomicInteger sends = new AtomicInteger();

        @SuppressWarnings("unchecked")
        private void attachTo(SseEmitter emitter) throws ReflectiveOperationException {
            Class<?> handlerType = Class.forName(
                    "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
            Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[] {handlerType},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "onCompletion" -> completion.set((Runnable) args[0]);
                            case "onTimeout" -> timeout.set((Runnable) args[0]);
                            case "onError" -> error.set((Consumer<Throwable>) args[0]);
                            case "send" -> sends.incrementAndGet();
                            default -> {
                            }
                        }
                        return null;
                    });
            Method initialize = emitter.getClass().getSuperclass()
                    .getDeclaredMethod("initialize", handlerType);
            initialize.setAccessible(true);
            initialize.invoke(emitter, handler);
        }
    }

    @Test
    void aBurstThatOverflowsTheQueueWhileSubscribingDoesNotLeakTheSubscription() throws Exception {
        source.beforeReturn = listener -> {
            for (int i = 0; i < QUEUE_CAPACITY + 1; i++) {
                listener.accept("{\"id\":\"" + i + "\"}");
            }
        };

        SseEmitter emitter = streams.open(TENANT, null);

        assertThat(source.closeCalls).hasValue(1);
        assertThat(source.active).hasValue(0);
        assertThat(admission.openStreams()).isZero();

        // The stream was finished before the container took it over: nothing is ever written to it.
        Callbacks callbacks = new Callbacks();
        callbacks.attachTo(emitter);
        Thread.sleep(100);
        assertThat(source.closeCalls).hasValue(1);
        assertThat(source.active).hasValue(0);
        assertThat(admission.openStreams()).isZero();
        assertThat(callbacks.sends).hasValue(0);
    }

    @Test
    void closingRacingAgainstAttachingReleasesTheSubscriptionExactlyOnce() throws Exception {
        for (int round = 0; round < 300; round++) {
            Thread[] burst = new Thread[1];
            int spins = round * 2_000;
            source.beforeReturn = listener -> {
                burst[0] = Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < QUEUE_CAPACITY + 1; i++) {
                        listener.accept("{\"id\":\"" + i + "\"}");
                    }
                });
                // Returning later and later sweeps the close through every position relative to the attach.
                for (int i = 0; i < spins; i++) {
                    Thread.onSpinWait();
                }
            };

            SseEmitter emitter = streams.open(TENANT, null);
            burst[0].join();
            // The writer may have drained the queue so that nothing overflowed; end such a stream like a timeout.
            Callbacks callbacks = new Callbacks();
            callbacks.attachTo(emitter);
            if (callbacks.timeout.get() != null) {
                callbacks.timeout.get().run();
            }

            assertThat(source.closeCalls).as("round %d", round).hasValue(round + 1);
            assertThat(source.active).as("round %d", round).hasValue(0);
            assertThat(admission.openStreams()).as("round %d", round).isZero();
        }
    }

    @Test
    void everyEndOfAnOpenStreamReleasesItOnceNoMatterHowOftenItIsSignalled() throws Exception {
        Callbacks callbacks = new Callbacks();
        SseEmitter emitter = streams.open(TENANT, Instant.now().plusSeconds(60));
        callbacks.attachTo(emitter);
        assertThat(source.active).hasValue(1);
        assertThat(admission.openStreams()).isEqualTo(1);

        callbacks.timeout.get().run();
        callbacks.completion.get().run();
        callbacks.error.get().accept(new IllegalStateException("broken pipe"));
        callbacks.completion.get().run();

        assertThat(source.closeCalls).hasValue(1);
        assertThat(source.active).hasValue(0);
        assertThat(admission.openStreams()).isZero();
    }

    @Test
    void aFailingSubscribeReleasesTheSlot() {
        // A subscribe that fails releases the slot without ever holding a subscription.
        source.beforeReturn = listener -> {
            throw new IllegalStateException("bus down");
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> streams.open(TENANT, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(source.closeCalls).hasValue(0);
        assertThat(admission.openStreams()).isZero();
    }
}
