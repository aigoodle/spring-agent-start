package io.github.aigoodle.completion.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseBridgeTest {

    @Test
    void assignsMonotonicIdsAndPreservesEventNames() {
        List<ServerSentEvent<Object>> events = SseBridge.stream(emitter -> {
                    emitter.event("chat_started", "start");
                    emitter.event("message", "hello");
                })
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::id)
                .containsExactly("1", "2");
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("chat_started", "message");
    }
}
