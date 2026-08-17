package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AppConversationEntity;
import io.github.aigoodle.memory.MemoryManager;
import io.github.aigoodle.agent.mapper.AppConversationMapper;
import io.github.aigoodle.common.exception.PlatformException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD for {@link AppConversationEntity} — the chat sessions grouping messages
 * under an app. The runtime auto-creates rows on the first turn of a new
 * conversation id so users of the memory API don't need to touch this layer;
 * this service exists to serve the console (list / rename / pin / archive).
 */
public class AppConversationService {

    private static final String DEFAULT_TENANT_ID = "default";
    private static final int GENERATED_NAME_MAX_LENGTH = 60;

    private final AppConversationMapper conversationMapper;
    private final MemoryManager memoryManager;

    public AppConversationService(AppConversationMapper conversationMapper, MemoryManager memoryManager) {
        this.conversationMapper = conversationMapper;
        this.memoryManager = memoryManager;
    }

    public List<AppConversationEntity> listByApp(String appId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AppConversationEntity>()
                .eq(AppConversationEntity::getAppId, appId)
                .orderByDesc(AppConversationEntity::getPinned)
                .orderByDesc(AppConversationEntity::getUpdatedAt));
    }

    public AppConversationEntity require(String conversationId) {
        AppConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new PlatformException("conversation_not_found",
                    "Conversation not found: " + conversationId, null);
        }
        return conversation;
    }

    /**
     * Idempotent upsert: called by the chat runtime the first time a
     * conversation id is seen. Never overwrites an existing row's name.
     */
    @Transactional
    public AppConversationEntity ensure(String conversationId, String appId,
                                     String tenantId, String firstMessage) {
        AppConversationEntity existingConversation = conversationMapper.selectById(conversationId);
        if (existingConversation != null) {
            return existingConversation;
        }

        AppConversationEntity newConversation = new AppConversationEntity();
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
    public AppConversationEntity rename(String conversationId, String name) {
        AppConversationEntity conversation = require(conversationId);
        conversation.setName(name);
        conversationMapper.updateById(conversation);
        return conversation;
    }

    @Transactional
    public AppConversationEntity togglePinned(String conversationId, boolean pinned) {
        AppConversationEntity conversation = require(conversationId);
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
        AppConversationEntity conversation = require(conversationId);
        conversationMapper.deleteById(conversationId);
        memoryManager.forgetConversation(conversation.getTenantId(), conversation.getAppId(), conversationId);
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
