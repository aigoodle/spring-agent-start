package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregate per-app conversation metrics derived from the {@code messages} table
 * — powers the "监测" tab in the app design drawer.
 * <p>
 * Kept intentionally light: everything is derivable from what {@code AgentMemory}
 * already persists, so wiring a monitor UI required no new writes. Costs and
 * tokens come from a separate observability stream ({@code llm_calls}) which
 * doesn't carry an {@code app_id} column yet, so those numbers are surfaced as
 * globals by the frontend.
 */
public class AppMetricsService {

    private final AgentMessageMapper messageMapper;

    public AppMetricsService(AgentMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    public AppMetricsView compute(String appId) {
        List<AgentMessageEntity> all = messageMapper.selectList(
                new LambdaQueryWrapper<AgentMessageEntity>()
                        .eq(AgentMessageEntity::getAgentId, appId));

        int totalMessages = all.size();
        Set<String> conversationIds = new HashSet<>();
        int userMessages = 0;
        int assistantMessages = 0;
        LocalDateTime lastActivity = null;

        for (AgentMessageEntity m : all) {
            if (m.getConversationId() != null) {
                conversationIds.add(m.getConversationId());
            }
            if ("USER".equalsIgnoreCase(m.getRole())) userMessages++;
            else if ("ASSISTANT".equalsIgnoreCase(m.getRole())) assistantMessages++;

            if (m.getUpdatedAt() != null && (lastActivity == null || m.getUpdatedAt().isAfter(lastActivity))) {
                lastActivity = m.getUpdatedAt();
            }
        }

        int totalConversations = conversationIds.size();
        // Prefer user-message count as "interactions" — one round-trip per user turn.
        double avgInteractions = totalConversations == 0
                ? 0.0
                : ((double) userMessages) / totalConversations;

        return AppMetricsView.builder()
                .appId(appId)
                .totalConversations(totalConversations)
                .totalMessages(totalMessages)
                .userMessages(userMessages)
                .assistantMessages(assistantMessages)
                .avgInteractionsPerConversation(round(avgInteractions))
                .lastActivityAt(lastActivity == null ? null : lastActivity.toString())
                .build();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
