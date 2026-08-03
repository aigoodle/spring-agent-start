package io.github.aigoodle.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversation memory persisted to the relational database, so it survives restarts
 * and is shared across instances. Loads a trailing window of messages.
 */
public class JdbcAgentMemory implements AgentMemory {

    private final AgentMessageMapper messageMapper;

    public JdbcAgentMemory(AgentMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public List<AgentMessage> load(String conversationId, int maxMessages) {
        if (conversationId == null) {
            return List.of();
        }
        List<AgentMessageEntity> storedMessages = messageMapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getConversationId, conversationId)
                .orderByDesc(AgentMessageEntity::getSeq)
                .last("limit " + Math.max(1, maxMessages)));
        List<AgentMessage> conversationMessages = new ArrayList<>(storedMessages.size());
        // Stored messages are newest-first; callers consume them oldest-first.
        for (int index = storedMessages.size() - 1; index >= 0; index--) {
            AgentMessageEntity storedMessage = storedMessages.get(index);
            conversationMessages.add(new AgentMessage(
                    AgentMessage.Role.valueOf(storedMessage.getRole()),
                    storedMessage.getContent()));
        }
        return conversationMessages;
    }

    @Override
    @Transactional
    public void append(String conversationId, String agentId, AgentMessage message) {
        if (conversationId == null) {
            return;
        }
        long latestSequence = messageMapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                        .select(AgentMessageEntity::getSeq)
                        .eq(AgentMessageEntity::getConversationId, conversationId)
                        .orderByDesc(AgentMessageEntity::getSeq)
                        .last("limit 1"))
                .stream().map(AgentMessageEntity::getSeq).findFirst().orElse(0L);

        AgentMessageEntity storedMessage = new AgentMessageEntity();
        storedMessage.setConversationId(conversationId);
        storedMessage.setAgentId(agentId);
        storedMessage.setRole(message.role().name());
        storedMessage.setContent(message.content());
        storedMessage.setSeq(latestSequence + 1);
        messageMapper.insert(storedMessage);
    }

    /**
     * Distinct conversations for an agent, newest first. Simple enough for the volumes
     * a single-app tool sees — for very large chats you'd add a proper aggregate query.
     */
    @Override
    public List<ConversationSummary> listConversations(String agentId, int limit) {
        if (agentId == null) {
            return List.of();
        }
        int conversationLimit = Math.min(200, Math.max(1, limit));
        List<AgentMessageEntity> storedMessages = messageMapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getAgentId, agentId)
                .orderByDesc(AgentMessageEntity::getCreatedAt));
        Map<String, AgentMessageEntity> latestMessageByConversation = new LinkedHashMap<>();
        Map<String, AgentMessageEntity> firstUserMessageByConversation = new LinkedHashMap<>();
        for (AgentMessageEntity storedMessage : storedMessages) {
            latestMessageByConversation.putIfAbsent(
                    storedMessage.getConversationId(), storedMessage);
            if ("USER".equalsIgnoreCase(storedMessage.getRole())) {
                // Rows come newest-first, so overwriting means the earliest user
                // message per conversation wins after the whole loop.
                firstUserMessageByConversation.put(
                        storedMessage.getConversationId(), storedMessage);
            }
        }
        List<ConversationSummary> conversationSummaries = new ArrayList<>();
        for (Map.Entry<String, AgentMessageEntity> entry
                : latestMessageByConversation.entrySet()) {
            String conversationId = entry.getKey();
            AgentMessageEntity firstUserMessage =
                    firstUserMessageByConversation.get(conversationId);
            String preview = firstUserMessage == null
                    ? "" : truncate(firstUserMessage.getContent());
            AgentMessageEntity latestMessage = entry.getValue();
            String updatedAt = latestMessage.getCreatedAt() == null
                    ? null : latestMessage.getCreatedAt().toString();
            conversationSummaries.add(
                    new ConversationSummary(conversationId, preview, updatedAt));
            if (conversationSummaries.size() >= conversationLimit) {
                break;
            }
        }
        return conversationSummaries;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        int maximumLength = 80;
        return text.length() <= maximumLength
                ? text
                : text.substring(0, maximumLength - 1) + "…";
    }
}
