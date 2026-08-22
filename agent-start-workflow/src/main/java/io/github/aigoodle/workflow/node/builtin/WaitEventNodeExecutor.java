package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeType;

public final class WaitEventNodeExecutor extends AbstractWaitNodeExecutor {
    @Override public NodeType type() { return NodeType.WAIT_EVENT; }
}
