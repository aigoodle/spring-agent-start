package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.common.exception.AgentException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an {@link AgentStrategy} by type. Custom strategy beans override built-ins.
 */
public class AgentStrategyRegistry {

    private final Map<AgentStrategyType, AgentStrategy> strategies = new EnumMap<>(AgentStrategyType.class);

    public AgentStrategyRegistry(List<AgentStrategy> beans) {
        if (beans != null) {
            beans.forEach(s -> strategies.put(s.type(), s));
        }
    }

    public AgentStrategy get(AgentStrategyType type) {
        AgentStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new AgentException("strategy_not_found",
                    "No agent strategy for " + type + " (have: " + strategies.keySet() + ")", null);
        }
        return strategy;
    }
}
