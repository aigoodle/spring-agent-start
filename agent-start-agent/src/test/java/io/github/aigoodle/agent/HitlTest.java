package io.github.aigoodle.agent;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.agent.strategy.AgentRunContext;
import io.github.aigoodle.agent.strategy.ReActStrategy;
import io.github.aigoodle.agent.support.ScriptedChatModel;
import io.github.aigoodle.tool.builtin.CalculatorTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Human-in-the-loop behaviour of the ReAct strategy, tested deterministically with a
 * scripted model and a calculator tool that requires approval.
 */
class HitlTest {

    private AgentRunContext context(ApprovalGate gate) {
        ScriptedChatModel model = new ScriptedChatModel(last ->
                last.startsWith("Observation:")
                        ? "Thought: done\nFinal Answer: " + last.replace("Observation:", "").strip()
                        : "Thought: I will compute\nAction: calculator\nAction Input: {\"expression\":\"2+2\"}");
        AgentDefinition def = AgentDefinition.builder()
                .id("a1").name("calc-agent").instructions("You compute.")
                .approvalRequiredTools(Set.of("calculator"))
                .maxIterations(4)
                .build();
        return AgentRunContext.builder()
                .definition(def)
                .query("what is 2+2?")
                .conversationId("c-hitl")
                .history(List.of())
                .chatClient(ChatClient.create(model))
                .tools(List.of(new CalculatorTool()))
                .approvalGate(gate)
                .build();
    }

    @Test
    void deniedToolYieldsDenialObservation() {
        AgentResponse r = new ReActStrategy().run(context(tc -> ApprovalGate.Decision.DENY));
        assertEquals(AgentResponse.Status.COMPLETED, r.getStatus());
        assertTrue(r.getText().toLowerCase().contains("denied"),
                "final answer should reflect the denial, was: " + r.getText());
    }

    @Test
    void pendingToolPausesWithAwaitingApproval() {
        AgentResponse r = new ReActStrategy().run(context(tc -> ApprovalGate.Decision.PENDING));
        assertEquals(AgentResponse.Status.AWAITING_APPROVAL, r.getStatus());
        assertNotNull(r.getPendingApproval());
        assertEquals("calculator", r.getPendingApproval().getToolName());
        assertNotNull(r.getPendingApproval().getApprovalId());
    }

    @Test
    void approvedToolExecutesAndAnswers() {
        AgentResponse r = new ReActStrategy().run(context(tc -> ApprovalGate.Decision.APPROVE));
        assertEquals(AgentResponse.Status.COMPLETED, r.getStatus());
        assertTrue(r.getText().contains("4"), "approved calc should yield 4, was: " + r.getText());
    }
}
