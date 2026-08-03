package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.adapter.AgentToolCallback;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Adapts agent tools for approval checks and observable Spring AI tool calls. */
final class FunctionCallingToolCallbacks {

    List<ToolCallback> create(AgentRunContext context, AgentResponse response) {
        AgentDefinition definition = context.getDefinition();
        List<ToolCallback> callbacks = new ArrayList<>();
        for (AgentTool tool : context.getTools()) {
            AgentTool executableTool = requiresApproval(definition, tool)
                    ? new ApprovalGuardedTool(
                            tool, definition, context.getConversationId(), context.getApprovalGate())
                    : tool;
            AgentTool observableTool = new StepRecordingTool(executableTool, response, context);
            callbacks.add(new AgentToolCallback(observableTool));
        }
        return callbacks;
    }

    private static boolean requiresApproval(AgentDefinition definition, AgentTool tool) {
        return definition.getApprovalRequiredTools() != null
                && definition.getApprovalRequiredTools().contains(tool.name());
    }

    /** Publishes action and observation steps around an internally executed tool call. */
    private record StepRecordingTool(AgentTool target,
                                     AgentResponse response,
                                     AgentRunContext context) implements AgentTool {

        @Override
        public String name() {
            return target.name();
        }

        @Override
        public String description() {
            return target.description();
        }

        @Override
        public String inputSchema() {
            return target.inputSchema();
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            recordAction(arguments);
            Object result = executeSafely(arguments);
            recordObservation(result);
            return result;
        }

        private Object executeSafely(Map<String, Object> arguments) {
            try {
                return target.execute(arguments);
            } catch (Exception exception) {
                return "error: " + exception.getMessage();
            }
        }

        private void recordAction(Map<String, Object> arguments) {
            AgentStep actionStep = AgentStep.action(target.name(), String.valueOf(arguments));
            response.addStep(actionStep);
            context.publishStep(actionStep);
        }

        private void recordObservation(Object result) {
            AgentStep observationStep = AgentStep.observation(
                    result == null ? "" : String.valueOf(result));
            response.addStep(observationStep);
            context.publishStep(observationStep);
        }
    }

    /** Consults the configured approval gate before invoking a sensitive tool. */
    private record ApprovalGuardedTool(AgentTool target,
                                       AgentDefinition definition,
                                       String conversationId,
                                       ApprovalGate approvalGate) implements AgentTool {

        @Override
        public String name() {
            return target.name();
        }

        @Override
        public String description() {
            return target.description();
        }

        @Override
        public String inputSchema() {
            return target.inputSchema();
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            ApprovalGate.Decision decision = review(arguments);
            if (decision == ApprovalGate.Decision.APPROVE) {
                return target.execute(arguments);
            }
            return "Tool '" + target.name() + "' was not approved (" + decision + ").";
        }

        private ApprovalGate.Decision review(Map<String, Object> arguments) {
            ApprovalGate.Decision decision = approvalGate.review(new ApprovalGate.ToolCall(
                    definition.getId(), conversationId, target.name(), String.valueOf(arguments)));
            return decision == null ? ApprovalGate.Decision.DENY : decision;
        }
    }
}
