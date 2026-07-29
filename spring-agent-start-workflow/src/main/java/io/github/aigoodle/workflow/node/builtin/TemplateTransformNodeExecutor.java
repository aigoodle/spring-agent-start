package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

/**
 * Renders a template against the variable pool. Config: {@code template} (required),
 * {@code outputKey} (default {@code output}).
 */
public class TemplateTransformNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.TEMPLATE_TRANSFORM;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String template = node.getString("template", "");
        String rendered = VariableResolver.render(template, ctx.getPool());
        return NodeResult.of(node.getString("outputKey", "output"), rendered);
    }
}
