package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeType;

public final class SleepUntilNodeExecutor extends AbstractWaitNodeExecutor {
    @Override public NodeType type() { return NodeType.SLEEP_UNTIL; }
}
