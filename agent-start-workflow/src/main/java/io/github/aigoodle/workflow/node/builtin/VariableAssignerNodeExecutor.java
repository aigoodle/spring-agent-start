package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.util.List;
import java.util.Map;

/**
 * Writes a batch of named values into the variable pool. Config: {@code assignments}
 * — a list of {@code {name, value}} entries. Values are templated against the pool, so
 * assignments can copy or transform earlier node outputs. The values are also emitted
 * as this node's outputs, so downstream nodes can reference them by node id.
 */
public class VariableAssignerNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.VARIABLE_ASSIGNER;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        List<Map<String, Object>> assignments = node.getMapList("assignments");
        NodeResult result = NodeResult.empty();
        for (Map<String, Object> a : assignments) {
            Object rawName = a.get("name");
            if (rawName == null) {
                continue;
            }
            String name = String.valueOf(rawName);
            Object rawValue = a.get("value");
            Object value = rawValue instanceof String s
                    ? VariableResolver.render(s, ctx.getPool())
                    : rawValue;
            result.output(name, value);
        }
        return result;
    }
}
