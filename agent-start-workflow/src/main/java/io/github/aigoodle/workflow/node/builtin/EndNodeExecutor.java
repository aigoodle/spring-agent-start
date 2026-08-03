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
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        NodeResult result = NodeResult.empty();
        Object configuredOutputs = node.get("outputs");
        if (configuredOutputs instanceof Map<?, ?> outputs) {
            for (Map.Entry<?, ?> output : outputs.entrySet()) {
                String outputName = String.valueOf(output.getKey());
                String outputTemplate = String.valueOf(output.getValue());
                result.output(
                        outputName, VariableResolver.render(outputTemplate, context.getPool()));
            }
        }
        return result;
    }
}
