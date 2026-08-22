package io.github.aigoodle.workflow.node;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.variable.VariableResolver;

/**
 * Executes one node type. Implementations are Spring beans, so adding a new node type
 * is just publishing a new {@code NodeExecutor} bean — the n8n "node" extension model.
 */
public interface NodeExecutor {

    NodeType type();

    NodeResult execute(NodeDef node, ExecutionContext context);

    default NodeExecutionMode executionMode(NodeDef node) {
        return NodeExecutionMode.PURE;
    }

    default String idempotencyKey(NodeDef node, ExecutionContext context) {
        String configured = node.getString("idempotencyKey");
        if (configured != null && !configured.isBlank()) {
            return VariableResolver.render(configured, context.getPool());
        }
        return executionMode(node) == NodeExecutionMode.PURE ? null
                : context.getRunId() + ":" + node.getId();
    }

    default NodeRetryPolicy retryPolicy(NodeDef node) {
        return NodeRetryPolicy.from(node);
    }

    default NodeResultCachePolicy resultCachePolicy(NodeDef node) {
        return executionMode(node) == NodeExecutionMode.PURE
                ? NodeResultCachePolicy.RUN : NodeResultCachePolicy.PERSISTENT;
    }

    default boolean resumable(NodeDef node) {
        return executionMode(node) != NodeExecutionMode.SIDE_EFFECT
                || (node.getString("idempotencyKey") != null && !node.getString("idempotencyKey").isBlank());
    }

    default NodeCompensationHandler compensationHandler(NodeDef node) {
        return null;
    }

    default NodeExecutionPolicy policy(NodeDef node, ExecutionContext context) {
        return new NodeExecutionPolicy(executionMode(node), idempotencyKey(node, context), retryPolicy(node),
                resultCachePolicy(node), resumable(node), compensationHandler(node));
    }
}
