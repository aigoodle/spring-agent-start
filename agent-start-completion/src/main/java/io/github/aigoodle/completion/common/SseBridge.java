package io.github.aigoodle.completion.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reusable helper that turns a synchronous "producer + listener callbacks" style run
 * (like {@code WorkflowEngine.run(..., stepListener)}) into a real-time
 * {@link Flux} of {@link ServerSentEvent}s.
 * <p>
 * Under WebFlux, an SSE endpoint just returns a {@code Flux<ServerSentEvent<?>>} and
 * Netty pushes each element as an {@code event:}/{@code data:} block as it arrives.
 * The producer we're bridging (agent runs, workflow steps, LLM stream tokens) blocks
 * on the calling thread, so we hand it off to a virtual-thread scheduler and pump
 * events into a {@link FluxSink}.
 */
public final class SseBridge {

    private static final Logger log = LoggerFactory.getLogger(SseBridge.class);

    private static final Scheduler PRODUCER_SCHEDULER =
            Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor(), "sse-producer");

    private SseBridge() {
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
     * Start {@code producer} on a virtual thread and stream its events as
     * {@link ServerSentEvent}s. Errors surface as an {@code event: error} carrying
     * {@code {"message": "..."}} followed by an error terminal signal.
     */
    public static Flux<ServerSentEvent<Object>> stream(Producer producer) {
        return Flux.<ServerSentEvent<Object>>create(sink -> {
            AtomicLong seq = new AtomicLong();
            Emit emit = (name, data) -> {
                if (sink.isCancelled()) return;
                sink.next(ServerSentEvent.builder(data)
                        .id(Long.toString(seq.incrementAndGet()))
                        .event(name)
                        .build());
            };
            try {
                producer.run(emit);
                sink.complete();
            } catch (Exception e) {
                log.warn("SSE producer failed: {}", e.getMessage());
                if (!sink.isCancelled()) {
                    sink.next(ServerSentEvent.builder((Object) Map.of(
                                    "message", e.getMessage() == null ? "unknown" : e.getMessage()))
                            .event("error")
                            .build());
                }
                sink.error(e);
            }
        }).subscribeOn(PRODUCER_SCHEDULER);
    }
}
