package io.github.aigoodle.agent.api;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Input to an agent run.
 */
@Data
@Builder
public class AgentRequest {

    private String query;

    /** Conversation id for memory; a new one is generated when null. */
    private String conversationId;

    /** Extra variables available to the agent's instructions template. */
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    public static AgentRequest of(String query) {
        return AgentRequest.builder().query(query).build();
    }
}
