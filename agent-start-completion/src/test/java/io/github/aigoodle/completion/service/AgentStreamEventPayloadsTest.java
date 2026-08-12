package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.api.AgentResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamEventPayloadsTest {

    @Test
    void approvalEventCarriesDurableRunIdentity() {
        AgentResponse response = AgentResponse.forConversation("conversation-1");
        response.setRunId("run-1");
        response.awaitApproval(AgentResponse.PendingApproval.forTool(
                "approval-1", "calculator", "{\"expression\":\"2+2\"}"));

        assertThat(AgentStreamEventPayloads.approvalRequired("task-1", response))
                .containsEntry("task_id", "task-1")
                .containsEntry("run_id", "run-1")
                .containsEntry("conversation_id", "conversation-1")
                .containsKey("approval");
    }
}
