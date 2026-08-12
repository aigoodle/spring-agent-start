package io.github.aigoodle.agent.context;

import io.github.aigoodle.agent.api.AgentMessage;

import java.util.List;
import java.util.Optional;

/** SPI for compressing dialogue omitted by the context budget. */
@FunctionalInterface
public interface AgentContextCompactor {
    Optional<AgentMessage> compact(List<AgentMessage> omittedMessages, int maxCharacters);
}
