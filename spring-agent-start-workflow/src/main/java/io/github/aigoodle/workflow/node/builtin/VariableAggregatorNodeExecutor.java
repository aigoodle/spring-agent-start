package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;

import java.util.List;

/**
 * Outputs the first non-null value among a list of variable paths — used to merge
 * the results of converging branches. Config: {@code variables} = list of paths.
 */
public class VariableAggregatorNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.VARIABLE_AGGREGATOR;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        Object vars = node.get("variables");
        if (vars instanceof List<?> list) {
            for (Object path : (List<Object>) list) {
                Object value = ctx.getPool().get(String.valueOf(path));
                if (value != null && !String.valueOf(value).isBlank()) {
                    return NodeResult.of("output", value);
                }
            }
        }
        return NodeResult.of("output", null);
    }
}
