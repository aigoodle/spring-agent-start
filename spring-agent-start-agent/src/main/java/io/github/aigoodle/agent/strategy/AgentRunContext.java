package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.tool.AgentTool;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.function.Consumer;

/**
 * Everything a strategy needs to run one turn: the resolved agent, the user query,
 * loaded memory, a chat client bound to the agent's model, the resolved tools and the
 * approval gate.
 * <p>
 * Strategies that support real-time reasoning traces (the ReAct family, function-calling
 * loops, plan-execute) should call {@link #fireStep(AgentStep)} as soon as a step is
 * finalised. When {@link #stepListener} is null the call is a no-op, so existing
 * one-shot callers keep working unchanged.
 */
@Data
@Builder
public class AgentRunContext {

    private AgentDefinition definition;
    private String query;
    private String conversationId;
    private List<AgentMessage> history;
    private ChatClient chatClient;
    private List<AgentTool> tools;
    private ApprovalGate approvalGate;

    /** Called for each finalised {@link AgentStep}; {@code null} = no streaming. */
    private Consumer<AgentStep> stepListener;

    /**
     * Called for each token delta emitted by the underlying chat model while it
     * streams the FINAL answer. Non-null enables real "typewriter" SSE output —
     * strategies check this and switch from {@code .call().content()} to
     * {@code .stream().content()}. {@code null} keeps the legacy blocking path
     * for CLI / test callers that just want the full string.
     */
    private Consumer<String> tokenListener;

    /** Fire the listener, swallowing exceptions so a bad UI push never crashes the run. */
    public void fireStep(AgentStep step) {
        Consumer<AgentStep> l = this.stepListener;
        if (l == null || step == null) {
            return;
        }
        try {
            l.accept(step);
        } catch (Exception ignored) {
            // Streaming is best-effort by contract.
        }
    }

    /** Fire the token listener; safe no-op when streaming is not requested. */
    public void fireToken(String delta) {
        Consumer<String> l = this.tokenListener;
        if (l == null || delta == null || delta.isEmpty()) {
            return;
        }
        try {
            l.accept(delta);
        } catch (Exception ignored) {
            // Token streaming is best-effort — a broken SSE consumer must not
            // abort the underlying agent run.
        }
    }
}
