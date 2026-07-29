package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.adapter.AgentToolCallback;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Native tool-calling strategy: hands the tools to the model via Spring AI's
 * tool-calling loop. Sensitive tools are wrapped so the {@link ApprovalGate} can
 * deny/short-circuit them inline (full pause/resume HITL is provided by ReAct).
 */
public class FunctionCallingStrategy implements AgentStrategy {

    @Override
    public AgentStrategyType type() {
        return AgentStrategyType.FUNCTION_CALLING;
    }

    @Override
    public AgentResponse run(AgentRunContext ctx) {
        AgentDefinition def = ctx.getDefinition();
        AgentResponse response = new AgentResponse();
        response.setConversationId(ctx.getConversationId());

        List<ToolCallback> callbacks = new ArrayList<>();
        for (AgentTool tool : ctx.getTools()) {
            AgentTool effective = def.getApprovalRequiredTools().contains(tool.name())
                    ? new ApprovalCheckingTool(tool, def, ctx.getConversationId(), ctx.getApprovalGate())
                    : tool;
            // Wrap once more so the SSE listener sees per-tool events even inside Spring AI's loop.
            effective = new StepFiringTool(effective, response, ctx);
            callbacks.add(new AgentToolCallback(effective));
        }

        ChatClient.ChatClientRequestSpec spec = ctx.getChatClient().prompt();
        org.springframework.ai.chat.prompt.ChatOptions perApp = AgentChatOptionsFactory.build(def);
        if (perApp != null) {
            spec = spec.options(perApp);
        }
        if (def.getInstructions() != null && !def.getInstructions().isBlank()) {
            spec = spec.system(def.getInstructions());
        }
        List<Message> history = new ArrayList<>();
        for (AgentMessage h : ctx.getHistory()) {
            history.add(switch (h.role()) {
                case ASSISTANT -> new AssistantMessage(h.content());
                case SYSTEM -> new SystemMessage(h.content());
                default -> new UserMessage(h.content());
            });
        }
        if (!history.isEmpty()) {
            spec = spec.messages(history);
        }
        spec = spec.user(ctx.getQuery());
        if (!callbacks.isEmpty()) {
            spec = spec.toolCallbacks(callbacks.toArray(new ToolCallback[0]));
        }

        String text;
        if (ctx.getTokenListener() != null) {
            // Real streaming: relay each chunk to the SSE emitter as it arrives.
            // Spring AI's tool-calling stream API transparently handles a
            // multi-turn tool call loop — the Flux only emits the final answer's
            // tokens, so we can safely append them all into the response text.
            StringBuilder sb = new StringBuilder();
            spec.stream().content()
                    .doOnNext(delta -> {
                        if (delta == null || delta.isEmpty()) return;
                        sb.append(delta);
                        ctx.fireToken(delta);
                    })
                    .blockLast();
            text = sb.toString();
        } else {
            text = spec.call().content();
        }
        response.setText(text);
        response.setStatus(AgentResponse.Status.COMPLETED);
        AgentStep finalStep = AgentStep.of(AgentStep.Kind.FINAL, text);
        response.addStep(finalStep);
        ctx.fireStep(finalStep);
        return response;
    }

    /**
     * Wrap the delegate tool so that each invocation records an ACTION step (with args)
     * and an OBSERVATION step (with result) — so SSE listeners can render the
     * tool-calling loop in real time even though Spring AI internally runs it as a
     * single chain.
     */
    private record StepFiringTool(AgentTool delegate, AgentResponse response,
                                  AgentRunContext ctx) implements AgentTool {
        @Override public String name() { return delegate.name(); }
        @Override public String description() { return delegate.description(); }
        @Override public String inputSchema() { return delegate.inputSchema(); }

        @Override
        public Object execute(Map<String, Object> args) {
            AgentStep actionStep = new AgentStep();
            actionStep.setKind(AgentStep.Kind.ACTION);
            actionStep.setAction(delegate.name());
            actionStep.setActionInput(String.valueOf(args));
            response.addStep(actionStep);
            ctx.fireStep(actionStep);

            Object result;
            try {
                result = delegate.execute(args);
            } catch (Exception e) {
                result = "error: " + e.getMessage();
            }
            AgentStep obs = new AgentStep();
            obs.setKind(AgentStep.Kind.OBSERVATION);
            obs.setObservation(result == null ? "" : String.valueOf(result));
            response.addStep(obs);
            ctx.fireStep(obs);
            return result;
        }
    }

    /** Wraps a tool so the approval gate runs before the real execution. */
    private record ApprovalCheckingTool(AgentTool delegate, AgentDefinition def,
                                        String conversationId, ApprovalGate gate) implements AgentTool {
        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public String inputSchema() {
            return delegate.inputSchema();
        }

        @Override
        public Object execute(Map<String, Object> args) {
            ApprovalGate.Decision decision = gate.review(new ApprovalGate.ToolCall(
                    def.getId(), conversationId, delegate.name(), String.valueOf(args)));
            if (decision == ApprovalGate.Decision.APPROVE) {
                return delegate.execute(args);
            }
            return "Tool '" + delegate.name() + "' was not approved (" + decision + ").";
        }
    }
}
