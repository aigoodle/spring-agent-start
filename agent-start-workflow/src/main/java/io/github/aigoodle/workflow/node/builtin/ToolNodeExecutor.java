package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Directly invokes a registered tool by name (no LLM in the loop). Config:
 * {@code tool} (name), {@code args} (map whose string values are templated),
 * {@code outputKey} (default {@code result}). Available only when the tools module is
 * on the classpath.
 */
public class ToolNodeExecutor implements NodeExecutor {

    private final ToolRegistry toolRegistry;

    public ToolNodeExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public NodeType type() {
        return NodeType.TOOL;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        String toolName = node.getString("tool");
        if (toolName == null) {
            return NodeResult.failure("Tool node requires a 'tool' name");
        }
        Map<String, Object> resolvedArguments = new HashMap<>();
        Object configuredArguments = node.get("args");
        if (configuredArguments instanceof Map<?, ?> arguments) {
            for (Map.Entry<?, ?> argument : arguments.entrySet()) {
                Object configuredValue = argument.getValue();
                Object resolvedValue = configuredValue instanceof String template
                        ? VariableResolver.render(template, context.getPool())
                        : configuredValue;
                resolvedArguments.put(String.valueOf(argument.getKey()), resolvedValue);
            }
        }
        Object toolResult = toolRegistry.execute(toolName, resolvedArguments);
        return NodeResult.of(node.getString("outputKey", "result"), toolResult);
    }
}
