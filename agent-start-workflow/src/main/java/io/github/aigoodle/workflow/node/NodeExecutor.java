package io.github.aigoodle.workflow.node;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;

/**
 * Executes one node type. Implementations are Spring beans, so adding a new node type
 * is just publishing a new {@code NodeExecutor} bean — the n8n "node" extension model.
 */
public interface NodeExecutor {

    NodeType type();

    NodeResult execute(NodeDef node, ExecutionContext context);
}
