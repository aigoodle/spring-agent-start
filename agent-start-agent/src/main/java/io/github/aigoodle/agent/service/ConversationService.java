package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;
import io.github.aigoodle.agent.mapper.ConversationMapper;
import io.github.aigoodle.common.exception.AgentException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD for {@link ConversationEntity} — the chat sessions grouping messages
 * under an app. The runtime auto-creates rows on the first turn of a new
 * conversation id so users of the memory API don't need to touch this layer;
 * this service exists to serve the console (list / rename / pin / archive).
 */
public class ConversationService {

    private static final String DEFAULT_TENANT_ID = "default";
    private static final int GENERATED_NAME_MAX_LENGTH = 60;

    private final ConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    public ConversationService(ConversationMapper conversationMapper, AgentMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    public List<ConversationEntity> listByApp(String appId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getAppId, appId)
                .orderByDesc(ConversationEntity::getPinned)
                .orderByDesc(ConversationEntity::getUpdatedAt));
    }

    public ConversationEntity require(String conversationId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new AgentException("conversation_not_found",
                    "Conversation not found: " + conversationId, null);
        }
        return conversation;
    }

    /**
     * Idempotent upsert: called by the chat runtime the first time a
     * conversation id is seen. Never overwrites an existing row's name.
     */
    @Transactional
    public ConversationEntity ensure(String conversationId, String appId,
                                     String tenantId, String firstMessage) {
        ConversationEntity existingConversation = conversationMapper.selectById(conversationId);
        if (existingConversation != null) {
            return existingConversation;
        }

        ConversationEntity newConversation = new ConversationEntity();
        newConversation.setId(conversationId);
        newConversation.setAppId(appId);
        newConversation.setTenantId(resolveTenantId(tenantId));
        newConversation.setName(truncate(firstMessage, GENERATED_NAME_MAX_LENGTH));
        newConversation.setStatus("normal");
        newConversation.setPinned(false);
        newConversation.setFromSource("web");
        conversationMapper.insert(newConversation);
        return newConversation;
    }

    @Transactional
    public ConversationEntity rename(String conversationId, String name) {
        ConversationEntity conversation = require(conversationId);
        conversation.setName(name);
        conversationMapper.updateById(conversation);
        return conversation;
    }

    @Transactional
    public ConversationEntity togglePinned(String conversationId, boolean pinned) {
        ConversationEntity conversation = require(conversationId);
        conversation.setPinned(pinned);
        conversationMapper.updateById(conversation);
        return conversation;
    }

    /**
     * Hard delete — also removes the messages under this conversation so the
     * per-app metrics stay honest.
     */
    @Transactional
    public void delete(String conversationId) {
        conversationMapper.deleteById(conversationId);
        messageMapper.delete(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getConversationId, conversationId));
    }

    private static String resolveTenantId(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT_ID : tenantId;
    }

    private static String truncate(String text, int maximumLength) {
        if (text == null || text.length() <= maximumLength) {
            return text;
        }
        return text.substring(0, maximumLength - 1) + "…";
    }
}
