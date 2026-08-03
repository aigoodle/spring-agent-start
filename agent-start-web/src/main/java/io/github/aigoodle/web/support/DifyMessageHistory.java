package io.github.aigoodle.web.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provides ordered message rows and lightweight conversation-title generation. */
@Component
@ConditionalOnClass(AgentMessageMapper.class)
public final class DifyMessageHistory {

    private static final int TITLE_SCAN_SIZE = 20;
    private static final int MAX_TITLE_LENGTH = 60;

    private final AgentMessageMapper messageMapper;

    public DifyMessageHistory(AgentMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    public List<AgentMessageEntity> findAll(String conversationId) {
        return messageMapper.selectList(baseQuery(conversationId));
    }

    public String suggestTitle(String conversationId) {
        List<AgentMessageEntity> recentMessages = messageMapper.selectList(
                baseQuery(conversationId).last("limit " + TITLE_SCAN_SIZE));
        for (AgentMessageEntity message : recentMessages) {
            if (isNonEmptyUserMessage(message)) {
                return abbreviate(message.getContent().trim());
            }
        }
        return null;
    }

    private static LambdaQueryWrapper<AgentMessageEntity> baseQuery(String conversationId) {
        return new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getConversationId, conversationId)
                .orderByAsc(AgentMessageEntity::getSeq);
    }

    private static boolean isNonEmptyUserMessage(AgentMessageEntity message) {
        return "USER".equalsIgnoreCase(message.getRole())
                && message.getContent() != null
                && !message.getContent().isBlank();
    }

    private static String abbreviate(String title) {
        return title.length() > MAX_TITLE_LENGTH
                ? title.substring(0, MAX_TITLE_LENGTH) + "…"
                : title;
    }
}
