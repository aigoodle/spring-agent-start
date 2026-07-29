package io.github.aigoodle.workflow.engine;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.NodeExecutor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Maps {@link NodeType} to its {@link NodeExecutor}. Custom executor beans override
 * built-ins for the same type, and new types are picked up automatically.
 */
public class NodeExecutorRegistry {

    private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);

    public NodeExecutorRegistry(List<NodeExecutor> executorBeans) {
        if (executorBeans != null) {
            for (NodeExecutor e : executorBeans) {
                executors.put(e.type(), e);
            }
        }
    }

    public NodeExecutor get(NodeType type) {
        NodeExecutor executor = executors.get(type);
        if (executor == null) {
            throw new AgentException("no_executor",
                    "No executor registered for node type " + type + " (registered: " + executors.keySet() + ")",
                    null);
        }
        return executor;
    }

    public boolean has(NodeType type) {
        return executors.containsKey(type);
    }
}
