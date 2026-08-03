package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.agent.api.*;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.util.List;
import java.util.Locale;

/** Workflow adapter for the complete agent runtime (strategy, tools, HITL and memory). */
public class AgentNodeExecutor implements NodeExecutor {
    private final AgentService agentService;

    public AgentNodeExecutor(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public NodeType type() { return NodeType.AGENT; }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        String provider = firstNonBlank(node.getString("modelProvider"), node.getString("provider"));
        String model = firstNonBlank(node.getString("modelName"), node.getString("model"));
        if (provider == null || model == null) {
            return NodeResult.failure("Agent node requires modelProvider + modelName");
        }
        try {
            AgentDefinition definition = AgentDefinition.builder()
                    .id("workflow:" + node.getId())
                    .tenantId(firstNonBlank(context.getTenantId(), "default"))
                    .name(firstNonBlank(node.getTitle(), node.getId()))
                    .instructions(VariableResolver.render(node.getString("systemPrompt",
                            "You are a helpful assistant. Use tools when helpful."), context.getPool()))
                    .modelProvider(provider)
                    .modelName(model)
                    .strategy(strategy(node.getString("strategy", "react")))
                    .toolNames(toolNames(node))
                    .maxIterations(node.getInt("maxIterations", 6))
                    .memoryEnabled(booleanValue(node.get("memoryEnabled"), true))
                    .memoryWindow(node.getInt("memoryWindow", 20))
                    .build();
            String query = VariableResolver.render(node.getString("query", "{{#sys.query#}}"), context.getPool());
            AgentResponse response = agentService.runDefinition(definition, AgentRequest.builder()
                    .query(query).conversationId(context.getConversationId()).variables(context.getInputs()).build());
            if (response.getStatus() != AgentResponse.Status.COMPLETED) {
                return NodeResult.failure("Agent run ended with status " + response.getStatus());
            }
            return NodeResult.of("text", response.getText())
                    .output("conversationId", response.getConversationId())
                    .output("steps", response.getSteps());
        } catch (RuntimeException exception) {
            return NodeResult.failure("Agent node failed: " + exception.getMessage());
        }
    }

    private static AgentStrategyType strategy(String value) {
        try {
            return AgentStrategyType.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return AgentStrategyType.REACT;
        }
    }

    private static List<String> toolNames(NodeDef node) {
        Object tools = node.get("tools");
        if (!(tools instanceof List<?> list)) return List.of();
        return list.stream().map(item -> item instanceof java.util.Map<?, ?> map
                        ? firstNonBlank(string(map.get("name")), string(map.get("toolName"))) : string(item))
                .filter(value -> value != null && !value.isBlank()).toList();
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
