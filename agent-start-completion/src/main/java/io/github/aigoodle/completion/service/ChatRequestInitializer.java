package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

/** Ensures chat requests have a conversation identity and best-effort catalog record. */
final class ChatRequestInitializer {

    private final ObjectProvider<ConversationService> conversationServiceProvider;
    private final Logger logger;

    ChatRequestInitializer(ObjectProvider<ConversationService> conversationServiceProvider,
                           Logger logger) {
        this.conversationServiceProvider = conversationServiceProvider;
        this.logger = logger;
    }

    void initialize(AgentEntity application, OpenAIChatRequest request) {
        ensureConversationId(request);
        createConversationRecord(application, request);
    }

    private static void ensureConversationId(OpenAIChatRequest request) {
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            request.setConversationId(UUID.randomUUID().toString());
        }
    }

    private void createConversationRecord(AgentEntity application, OpenAIChatRequest request) {
        ConversationService conversationService = conversationServiceProvider.getIfAvailable();
        if (conversationService == null) {
            return;
        }
        try {
            conversationService.ensure(
                    request.getConversationId(),
                    application.getId(),
                    application.getTenantId(),
                    request.lastUserMessage());
        } catch (Exception exception) {
            logger.debug("Conversation upsert skipped: {}", exception.getMessage());
        }
    }
}
