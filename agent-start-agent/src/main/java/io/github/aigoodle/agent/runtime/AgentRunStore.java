package io.github.aigoodle.agent.runtime;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for executions and their append-only event stream. */
public interface AgentRunStore {

    AgentRunSnapshot create(String runId, AgentDefinition definition, AgentRequest request,
                            String conversationId);

    AgentRunSnapshot transition(String runId, AgentRunStatus target,
                                AgentResponse response, String error);

    Optional<AgentRunSnapshot> find(String runId);

    List<AgentRunEvent> events(String runId, long afterSequence, int limit);

    /** Append a domain event without changing the run lifecycle status. */
    void appendEvent(String runId, String type, String payloadJson);
}
