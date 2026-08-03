package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.agent.hitl.AutoApproveGate;
import io.github.aigoodle.tool.AgentTool;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.function.Consumer;

/** All resolved collaborators and inputs required to execute one agent turn. */
@Data
@Builder
public class AgentRunContext {

    private static final ApprovalGate DEFAULT_APPROVAL_GATE = new AutoApproveGate();

    private AgentDefinition definition;
    private String query;
    private String conversationId;

    @Builder.Default
    private List<AgentMessage> history = List.of();

    private ChatClient chatClient;

    @Builder.Default
    private List<AgentTool> tools = List.of();

    @Builder.Default
    private ApprovalGate approvalGate = DEFAULT_APPROVAL_GATE;

    /** Receives each completed reasoning step when step streaming is enabled. */
    private Consumer<AgentStep> stepListener;

    /** Receives answer deltas when token streaming is enabled. */
    private Consumer<String> tokenListener;

    /** Never exposes a nullable history collection to strategy implementations. */
    public List<AgentMessage> getHistory() {
        return history == null ? List.of() : history;
    }

    /** Never exposes a nullable tool collection to strategy implementations. */
    public List<AgentTool> getTools() {
        return tools == null ? List.of() : tools;
    }

    /** Falls back to the starter's opt-in approval policy when no custom gate is supplied. */
    public ApprovalGate getApprovalGate() {
        return approvalGate == null ? DEFAULT_APPROVAL_GATE : approvalGate;
    }

    public boolean isTokenStreamingEnabled() {
        return tokenListener != null;
    }

    /** Publishes a reasoning step without allowing a broken listener to abort the run. */
    public void publishStep(AgentStep step) {
        Consumer<AgentStep> listener = this.stepListener;
        if (listener == null || step == null) {
            return;
        }
        try {
            listener.accept(step);
        } catch (RuntimeException ignored) {
            // Streaming delivery is best effort by contract.
        }
    }

    /** Publishes a non-empty token delta without interrupting the underlying run. */
    public void publishToken(String tokenDelta) {
        Consumer<String> listener = this.tokenListener;
        if (listener == null || tokenDelta == null || tokenDelta.isEmpty()) {
            return;
        }
        try {
            listener.accept(tokenDelta);
        } catch (RuntimeException ignored) {
            // Streaming delivery is best effort by contract.
        }
    }

    /** @deprecated Use {@link #publishStep(AgentStep)}. */
    @Deprecated(forRemoval = false)
    public void fireStep(AgentStep step) {
        publishStep(step);
    }

    /** @deprecated Use {@link #publishToken(String)}. */
    @Deprecated(forRemoval = false)
    public void fireToken(String tokenDelta) {
        publishToken(tokenDelta);
    }
}
