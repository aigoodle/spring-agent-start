package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.util.Map;

/**
 * Produces the workflow's final outputs. Config {@code outputs} is a map of output
 * name to a template (e.g. {@code {"answer":"{{#llm.text#}}"}}). With no config, the
 * END node simply exposes whatever is referenced, defaulting to an empty result.
 */
public class EndNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.END;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        NodeResult result = NodeResult.empty();
        Object outputs = node.get("outputs");
        if (outputs instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                result.output(e.getKey(), VariableResolver.render(String.valueOf(e.getValue()), ctx.getPool()));
            }
        }
        return result;
    }
}
