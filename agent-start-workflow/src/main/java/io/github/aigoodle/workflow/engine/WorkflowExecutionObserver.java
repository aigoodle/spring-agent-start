package io.github.aigoodle.workflow.engine;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeResult;

/** Persistence/telemetry hook invoked at deterministic scheduler boundaries. */
public interface WorkflowExecutionObserver {
    WorkflowExecutionObserver NOOP = new WorkflowExecutionObserver() {};

    default void nodeScheduled(NodeDef node, int attempt, ExecutionContext context) {}
    default void nodeStarted(NodeDef node, int attempt, ExecutionContext context) {}
    default void nodeFinished(NodeDef node, NodeExecutionStatus status, NodeResult result,
                              int attempt, ExecutionContext context) {}
    default void iterationProgress(String nodeId, Object cursor, ExecutionContext context) {}
}
