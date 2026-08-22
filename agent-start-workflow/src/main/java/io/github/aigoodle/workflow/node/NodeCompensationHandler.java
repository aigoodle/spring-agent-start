package io.github.aigoodle.workflow.node;

import io.github.aigoodle.workflow.graph.NodeDef;

@FunctionalInterface
public interface NodeCompensationHandler {
    NodeResult compensate(NodeDef node, ExecutionContext context, NodeResult originalResult);
}
