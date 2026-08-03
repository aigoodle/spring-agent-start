package io.github.aigoodle.trigger.service;

import io.github.aigoodle.trigger.api.InvocationStatus;
import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.dispatch.TriggerDispatcher;
import io.github.aigoodle.trigger.dispatch.TriggerDispatcherRegistry;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.trigger.mapper.TriggerInvocationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TriggerInvocationRunnerTest {

    @Test
    void recordsDispatcherFailureDetails() {
        TriggerInvocationMapper invocationMapper = mock(TriggerInvocationMapper.class);
        TriggerDispatcher dispatcher = dispatcherReturning(DispatchResult.builder()
                .success(false)
                .runId("run-1")
                .outputs(Map.of("accepted", false))
                .error("target rejected request")
                .build());
        TriggerInvocationRunner runner = new TriggerInvocationRunner(
                invocationMapper, new TriggerDispatcherRegistry(List.of(dispatcher)));
        TriggerInvocationEntity invocation = invocation("invocation-1");

        runner.execute(trigger("trigger-1", "agent"), invocation, Map.of());

        assertThat(invocation.getStatus()).isEqualTo(InvocationStatus.FAILED);
        assertThat(invocation.getError()).isEqualTo("target rejected request");
        assertThat(invocation.getRunId()).isEqualTo("run-1");
        assertThat(invocation.getOutputsJson()).isEqualTo("{\"accepted\":false}");
        verify(invocationMapper, times(2)).updateById(invocation);
    }

    @Test
    void recordsFailedStatusWhenDispatcherThrows() {
        TriggerInvocationMapper invocationMapper = mock(TriggerInvocationMapper.class);
        TriggerDispatcherRegistry registry = new TriggerDispatcherRegistry(List.of(failingDispatcher()));
        TriggerInvocationRunner runner = new TriggerInvocationRunner(invocationMapper, registry);
        TriggerEntity trigger = trigger("trigger-1", "agent");
        TriggerInvocationEntity invocation = invocation("invocation-1");

        DispatchResult result = runner.execute(trigger, invocation, Map.of("query", "hello"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("dispatcher unavailable");
        assertThat(invocation.getStatus()).isEqualTo(InvocationStatus.FAILED);
        assertThat(invocation.getError()).isEqualTo("dispatcher unavailable");
        verify(invocationMapper, times(2)).updateById(invocation);
    }

    private static TriggerDispatcher failingDispatcher() {
        return new TriggerDispatcher() {
            @Override
            public String targetType() {
                return "agent";
            }

            @Override
            public DispatchResult dispatch(String targetId,
                                           Map<String, Object> inputs,
                                           String conversationId) {
                throw new IllegalStateException("dispatcher unavailable");
            }
        };
    }

    private static TriggerDispatcher dispatcherReturning(DispatchResult result) {
        return new TriggerDispatcher() {
            @Override
            public String targetType() {
                return "agent";
            }

            @Override
            public DispatchResult dispatch(String targetId,
                                           Map<String, Object> inputs,
                                           String conversationId) {
                return result;
            }
        };
    }

    private static TriggerEntity trigger(String id, String targetType) {
        TriggerEntity trigger = new TriggerEntity();
        trigger.setId(id);
        trigger.setTargetType(targetType);
        trigger.setTargetId("target-1");
        return trigger;
    }

    private static TriggerInvocationEntity invocation(String id) {
        TriggerInvocationEntity invocation = new TriggerInvocationEntity();
        invocation.setId(id);
        invocation.setStatus(InvocationStatus.PENDING);
        return invocation;
    }
}
