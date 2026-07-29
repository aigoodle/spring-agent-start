package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public ConversationEntity require(String id) {
        ConversationEntity e = conversationMapper.selectById(id);
        if (e == null) {
            throw new AgentException("conversation_not_found", "Conversation not found: " + id, null);
        }
        return e;
    }

    /**
     * Idempotent upsert: called by the chat runtime the first time a
     * conversation id is seen. Never overwrites an existing row's name.
     */
    @Transactional
    public ConversationEntity ensure(String conversationId, String appId, String tenantId, String firstMessage) {
        ConversationEntity existing = conversationMapper.selectById(conversationId);
        if (existing != null) return existing;
        ConversationEntity fresh = new ConversationEntity();
        fresh.setId(conversationId);
        fresh.setAppId(appId);
        fresh.setTenantId(tenantId == null ? "default" : tenantId);
        fresh.setName(truncate(firstMessage, 60));
        fresh.setStatus("normal");
        fresh.setPinned(false);
        fresh.setFromSource("web");
        conversationMapper.insert(fresh);
        return fresh;
    }

    @Transactional
    public ConversationEntity rename(String id, String name) {
        ConversationEntity e = require(id);
        e.setName(name);
        conversationMapper.updateById(e);
        return e;
    }

    @Transactional
    public ConversationEntity togglePinned(String id, boolean pinned) {
        ConversationEntity e = require(id);
        e.setPinned(pinned);
        conversationMapper.updateById(e);
        return e;
    }

    /**
     * Hard delete — also removes the messages under this conversation so the
     * per-app metrics stay honest.
     */
    @Transactional
    public void delete(String id) {
        conversationMapper.deleteById(id);
        messageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<io.github.aigoodle.agent.entity.AgentMessageEntity>()
                .eq(io.github.aigoodle.agent.entity.AgentMessageEntity::getConversationId, id));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
