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

    private final AgentMessageMapper mapper;

    public JdbcAgentMemory(AgentMessageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AgentMessage> load(String conversationId, int maxMessages) {
        if (conversationId == null) {
            return List.of();
        }
        List<AgentMessageEntity> rows = mapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getConversationId, conversationId)
                .orderByDesc(AgentMessageEntity::getSeq)
                .last("limit " + Math.max(1, maxMessages)));
        List<AgentMessage> messages = new ArrayList<>();
        // rows are newest-first; reverse to oldest-first
        for (int i = rows.size() - 1; i >= 0; i--) {
            AgentMessageEntity r = rows.get(i);
            messages.add(new AgentMessage(AgentMessage.Role.valueOf(r.getRole()), r.getContent()));
        }
        return messages;
    }

    @Override
    @Transactional
    public void append(String conversationId, String agentId, AgentMessage message) {
        if (conversationId == null) {
            return;
        }
        Long maxSeq = mapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                        .select(AgentMessageEntity::getSeq)
                        .eq(AgentMessageEntity::getConversationId, conversationId)
                        .orderByDesc(AgentMessageEntity::getSeq)
                        .last("limit 1"))
                .stream().map(AgentMessageEntity::getSeq).findFirst().orElse(0L);

        AgentMessageEntity entity = new AgentMessageEntity();
        entity.setConversationId(conversationId);
        entity.setAgentId(agentId);
        entity.setRole(message.role().name());
        entity.setContent(message.content());
        entity.setSeq(maxSeq + 1);
        mapper.insert(entity);
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
        int cap = Math.min(200, Math.max(1, limit));
        List<AgentMessageEntity> rows = mapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getAgentId, agentId)
                .orderByDesc(AgentMessageEntity::getCreatedAt));
        Map<String, AgentMessageEntity> latestByConv = new LinkedHashMap<>();
        Map<String, AgentMessageEntity> firstUserByConv = new LinkedHashMap<>();
        for (AgentMessageEntity r : rows) {
            latestByConv.putIfAbsent(r.getConversationId(), r);
            if ("USER".equalsIgnoreCase(r.getRole())) {
                // Rows come newest-first, so overwriting means the earliest user
                // message per conversation wins after the whole loop.
                firstUserByConv.put(r.getConversationId(), r);
            }
        }
        List<ConversationSummary> out = new ArrayList<>();
        for (Map.Entry<String, AgentMessageEntity> e : latestByConv.entrySet()) {
            AgentMessageEntity first = firstUserByConv.get(e.getKey());
            String preview = first == null ? "" : truncate(first.getContent());
            String updatedAt = e.getValue().getCreatedAt() == null ? null : e.getValue().getCreatedAt().toString();
            out.add(new ConversationSummary(e.getKey(), preview, updatedAt));
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
