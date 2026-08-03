package io.github.aigoodle.web.support;

import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.web.dto.ChatRequest;

/** Maps the MVC chat payload onto the agent runtime request. */
public final class AgentRequestMapper {

    private AgentRequestMapper() {
    }

    public static AgentRequest from(ChatRequest request) {
        return AgentRequest.builder()
                .query(request.getQuery())
                .conversationId(request.getConversationId())
                .variables(request.getVariables())
                .build();
    }
}
