package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.support.AppAccessResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provides the console-oriented conversation and message history read model. */
@Service
public class ConversationHistoryService {

    private final ObjectProvider<ConversationService> conversationServices;
    private final ObjectProvider<AgentMemory> agentMemories;

    public ConversationHistoryService(ObjectProvider<ConversationService> conversationServices,
                                      ObjectProvider<AgentMemory> agentMemories) {
        this.conversationServices = conversationServices;
        this.agentMemories = agentMemories;
    }

    public List<Map<String, Object>> conversations(String appId, int limit) {
        ConversationService conversationService = conversationServices.getIfAvailable();
        if (conversationService == null) {
            return List.of();
        }
        AgentMemory memory = agentMemories.getIfAvailable();
        return conversationService.listByApp(appId).stream()
                .limit(limit)
                .map(conversation -> toConversationView(conversation, memory))
                .toList();
    }

    public List<Map<String, Object>> messages(String appId,
                                              String conversationId,
                                              int limit) {
        verifyOwnership(appId, conversationId);
        AgentMemory memory = agentMemories.getIfAvailable();
        if (memory == null) {
            return List.of();
        }
        return memory.load(conversationId, limit).stream()
                .map(ConversationHistoryService::toMessageView)
                .toList();
    }

    private Map<String, Object> toConversationView(
            ConversationEntity conversation, AgentMemory memory) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("conversationId", conversation.getId());
        view.put("name", conversation.getName());
        view.put("userId", userIdOf(conversation));
        view.put("firstMessage", firstMessageOf(conversation, memory));
        view.put("updatedAt", formatDateTime(
                conversation.getUpdatedAt(), conversation.getCreatedAt()));
        view.put("pinned", Boolean.TRUE.equals(conversation.getPinned()));
        return view;
    }

    private static Map<String, Object> toMessageView(AgentMessage message) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("role", message.role() == null ? null : message.role().name());
        view.put("content", message.content());
        return view;
    }

    private String firstMessageOf(ConversationEntity conversation, AgentMemory memory) {
        if (conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
            return conversation.getSummary();
        }
        if (memory == null) {
            return conversation.getName();
        }
        try {
            return memory.load(conversation.getId(), 20).stream()
                    .filter(message -> message.role() == AgentMessage.Role.USER)
                    .map(AgentMessage::content)
                    .findFirst()
                    .orElse(conversation.getName());
        } catch (RuntimeException memoryFailure) {
            return conversation.getName();
        }
    }

    private void verifyOwnership(String appId, String conversationId) {
        ConversationService conversationService = conversationServices.getIfAvailable();
        if (conversationService == null) {
            return;
        }
        ConversationEntity conversation = conversationService.require(conversationId);
        if (!Objects.equals(appId, conversation.getAppId())) {
            throw new AgentException("conversation_not_found",
                    "Conversation not found: " + conversationId, null);
        }
    }

    private static String userIdOf(ConversationEntity conversation) {
        String endUserId = AppAccessResolver.trimToNull(conversation.getFromEndUserId());
        return endUserId != null
                ? endUserId
                : AppAccessResolver.trimToNull(conversation.getFromAccountId());
    }

    private static String formatDateTime(LocalDateTime updatedAt, LocalDateTime createdAt) {
        LocalDateTime effectiveTime = updatedAt != null ? updatedAt : createdAt;
        return effectiveTime == null ? null : effectiveTime.toString();
    }
}
