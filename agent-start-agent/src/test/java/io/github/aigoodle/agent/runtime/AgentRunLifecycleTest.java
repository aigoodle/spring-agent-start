package io.github.aigoodle.agent.runtime;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunLifecycleTest {

    @Test
    void persistsOrderedLifecycleAndApprovalPause() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentDefinition definition = AgentDefinition.builder()
                .id("agent-1").tenantId("tenant-1").name("researcher").build();
        AgentRequest request = AgentRequest.of("research this");

        store.create("run-1", definition, request, "conversation-1");
        store.transition("run-1", AgentRunStatus.RUNNING, null, null);
        AgentResponse response = AgentResponse.forConversation("conversation-1");
        response.setRunId("run-1");
        response.awaitApproval(AgentResponse.PendingApproval.forTool(
                "approval-1", "shell", "{\"command\":\"pwd\"}"));
        store.transition("run-1", AgentRunStatus.WAITING_APPROVAL, response, null);

        AgentRunSnapshot run = store.find("run-1").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(run.version()).isEqualTo(2);
        assertThat(store.events("run-1", 0, 10))
                .extracting(AgentRunEvent::type)
                .containsExactly("RUN_CREATED", "RUN_RUNNING", "RUN_WAITING_APPROVAL");
    }

    @Test
    void rejectsTransitionsOutOfTerminalState() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentDefinition definition = AgentDefinition.builder().id("agent-1").build();
        store.create("run-1", definition, AgentRequest.of("hello"), "conversation-1");
        store.transition("run-1", AgentRunStatus.RUNNING, null, null);
        store.transition("run-1", AgentRunStatus.COMPLETED, new AgentResponse(), null);

        assertThatThrownBy(() -> store.transition(
                "run-1", AgentRunStatus.RUNNING, null, null))
                .hasMessageContaining("cannot transition");
    }
}
