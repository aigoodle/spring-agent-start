package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AgentRunContextTest {

    @Test
    void suppliesSafeDefaultsForOptionalRuntimeCollaborators() {
        AgentRunContext context = AgentRunContext.builder().build();

        assertThat(context.getHistory()).isEmpty();
        assertThat(context.getTools()).isEmpty();
        assertThat(context.getApprovalGate()).isNotNull();
        assertThat(context.getApprovalGate().review(
                new ApprovalGate.ToolCall("agent-1", "conversation-1", "search", "{}")))
                .isEqualTo(ApprovalGate.Decision.APPROVE);
        assertThat(context.isTokenStreamingEnabled()).isFalse();
    }

    @Test
    void protectsStrategiesWhenOptionalCollectionsAreExplicitlySetToNull() {
        AgentRunContext context = AgentRunContext.builder()
                .history(null)
                .tools(null)
                .approvalGate(null)
                .build();

        assertThat(context.getHistory()).isEmpty();
        assertThat(context.getTools()).isEmpty();
        assertThat(context.getApprovalGate()).isNotNull();
    }

    @Test
    void publishesOnlyMeaningfulEvents() {
        List<AgentStep> steps = new ArrayList<>();
        List<String> tokenDeltas = new ArrayList<>();
        AgentRunContext context = AgentRunContext.builder()
                .stepListener(steps::add)
                .tokenListener(tokenDeltas::add)
                .build();
        AgentStep actionStep = AgentStep.of(AgentStep.Kind.ACTION, "search");

        context.publishStep(null);
        context.publishStep(actionStep);
        context.publishToken(null);
        context.publishToken("");
        context.publishToken("answer");

        assertThat(steps).containsExactly(actionStep);
        assertThat(tokenDeltas).containsExactly("answer");
        assertThat(context.isTokenStreamingEnabled()).isTrue();
    }

    @Test
    void listenerFailuresDoNotAbortTheAgentRun() {
        AgentRunContext context = AgentRunContext.builder()
                .stepListener(step -> {
                    throw new IllegalStateException("disconnected");
                })
                .tokenListener(token -> {
                    throw new IllegalStateException("disconnected");
                })
                .build();

        assertThatCode(() -> {
            context.publishStep(AgentStep.of(AgentStep.Kind.FINAL, "done"));
            context.publishToken("done");
        }).doesNotThrowAnyException();
    }
}
