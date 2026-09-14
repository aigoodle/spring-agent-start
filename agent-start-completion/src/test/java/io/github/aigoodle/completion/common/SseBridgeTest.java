package io.github.aigoodle.completion.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseBridgeTest {

    @Test
    void carriesDepartmentContextAcrossProducerThread() {
        var user = io.github.aigoodle.common.context.CurrentUser.builder()
                .userId("member").tenantId("t1").departmentId("team")
                .departmentIds(java.util.Set.of("team", "subteam"))
                .roleIds(java.util.Set.of("role-1")).build();
        var stream = io.github.aigoodle.common.context.UserContextHolder.callAs(user,
                () -> SseBridge.stream(emitter -> {
                    assertThat(io.github.aigoodle.common.context.UserContextHolder.get()).isSameAs(user);
                    emitter.event("department", io.github.aigoodle.common.context.UserContextHolder.currentDepartmentId());
                }));
        assertThat(io.github.aigoodle.common.context.UserContextHolder.get()).isNull();
        assertThat(stream.blockFirst(Duration.ofSeconds(5)).data()).isEqualTo("team");
        assertThat(io.github.aigoodle.common.context.UserContextHolder.get()).isNull();
    }

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
