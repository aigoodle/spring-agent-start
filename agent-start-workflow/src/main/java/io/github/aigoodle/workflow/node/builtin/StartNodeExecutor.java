package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;

/**
 * Seeds the run's inputs as this node's outputs so downstream nodes can reference
 * them via {@code {{#start.field#}}}.
 */
public class StartNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.START;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        NodeResult result = NodeResult.empty();
        context.getInputs().forEach(result::output);
        return result;
    }
}
