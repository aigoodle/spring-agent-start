package io.github.aigoodle.web.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MVC-flavored replacement for the WebFlux SseBridge — turns a synchronous
 * "producer + listener callbacks" run into a real-time stream of
 * {@link SseEmitter} events.
 * <p>
 * Each call spawns a virtual thread that runs the producer and pushes events
 * through an {@link SseEmitter}. Tomcat's async request handling keeps the
 * connection open until the emitter completes or errors out.
 */
public final class SseEmitterBridge {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterBridge.class);

    /** Never timeout on server-side — clients disconnect when they're done. */
    private static final long NO_TIMEOUT = 0L;

    private static final ExecutorService PRODUCER_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private SseEmitterBridge() {
    }

    @FunctionalInterface
    public interface Emit {
        void event(String name, Object data);
    }

    @FunctionalInterface
    public interface Producer {
        void run(Emit emit) throws Exception;
    }

    /**
     * Start {@code producer} on a virtual thread and stream its events through
     * a new {@link SseEmitter}. Errors surface as an {@code event: error}
     * carrying {@code {"message": "..."}} followed by {@link SseEmitter#completeWithError}.
     */
    public static SseEmitter stream(Producer producer) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        Emit emit = (name, data) -> {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException e) {
                // Client disconnected — swallow to avoid noise; the producer
                // will surface any real error via completeWithError().
                throw new RuntimeException("SSE emit failed: " + e.getMessage(), e);
            }
        };
        PRODUCER_EXECUTOR.execute(() -> {
            try {
                producer.run(emit);
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE producer failed: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", e.getMessage() == null ? "unknown" : e.getMessage())));
                } catch (IOException ignore) {
                    // Client already gone; nothing to do.
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
