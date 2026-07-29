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
    @SuppressWarnings("unchecked")
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String toolName = node.getString("tool");
        if (toolName == null) {
            return NodeResult.failure("Tool node requires a 'tool' name");
        }
        Map<String, Object> args = new HashMap<>();
        Object rawArgs = node.get("args");
        if (rawArgs instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                Object v = e.getValue();
                args.put(e.getKey(), v instanceof String s ? VariableResolver.render(s, ctx.getPool()) : v);
            }
        }
        Object result = toolRegistry.execute(toolName, args);
        return NodeResult.of(node.getString("outputKey", "result"), result);
    }
}
