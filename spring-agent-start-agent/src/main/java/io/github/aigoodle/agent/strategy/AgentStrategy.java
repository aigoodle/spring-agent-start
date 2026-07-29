package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStrategyType;

/**
 * Pluggable agent reasoning strategy. Implementations are Spring beans selected by
 * {@link #type()}; publish a new one to add a custom strategy.
 */
public interface AgentStrategy {

    AgentStrategyType type();

    AgentResponse run(AgentRunContext context);
}
