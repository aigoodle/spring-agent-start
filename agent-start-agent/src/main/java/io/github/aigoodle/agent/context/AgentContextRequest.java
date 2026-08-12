package io.github.aigoodle.agent.context;

import io.github.aigoodle.agent.api.AgentDefinition;

/** Inputs needed to assemble a bounded, model-ready agent context. */
public record AgentContextRequest(AgentDefinition definition, String conversationId,
                                  String query, int maxCharacters) {
}
