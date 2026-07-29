package io.github.aigoodle.trigger.dispatch;

import io.github.aigoodle.common.exception.AgentException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link TriggerDispatcher} by target type.
 */
public class TriggerDispatcherRegistry {

    private final Map<String, TriggerDispatcher> dispatchers = new HashMap<>();

    public TriggerDispatcherRegistry(List<TriggerDispatcher> beans) {
        if (beans != null) {
            beans.forEach(d -> dispatchers.put(d.targetType().toLowerCase(), d));
        }
    }

    public TriggerDispatcher get(String targetType) {
        String key = (targetType == null ? WorkflowTriggerDispatcher.TARGET_TYPE : targetType).toLowerCase();
        TriggerDispatcher dispatcher = dispatchers.get(key);
        if (dispatcher == null) {
            throw new AgentException("dispatcher_not_found",
                    "No trigger dispatcher for target type '" + targetType + "' (have: " + dispatchers.keySet() + ")",
                    null);
        }
        return dispatcher;
    }
}
