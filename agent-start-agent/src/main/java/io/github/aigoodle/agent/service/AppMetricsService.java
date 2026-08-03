package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Derives an application's conversation metrics from its persisted messages. */
public class AppMetricsService {

    private static final String USER_ROLE = "USER";
    private static final String ASSISTANT_ROLE = "ASSISTANT";

    private final AgentMessageMapper messageMapper;

    public AppMetricsService(AgentMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    public AppMetricsView summarize(String appId) {
        List<AgentMessageEntity> messages = messageMapper.selectList(
                new LambdaQueryWrapper<AgentMessageEntity>()
                        .eq(AgentMessageEntity::getAgentId, appId));

        MetricsAccumulator metrics = new MetricsAccumulator();
        messages.forEach(metrics::include);
        return metrics.toView(appId, messages.size());
    }

    private static final class MetricsAccumulator {

        private final Set<String> conversationIds = new HashSet<>();
        private int userMessageCount;
        private int assistantMessageCount;
        private LocalDateTime mostRecentActivity;

        void include(AgentMessageEntity message) {
            if (message.getConversationId() != null) {
                conversationIds.add(message.getConversationId());
            }
            countRole(message.getRole());
            includeActivityTime(activityTime(message));
        }

        AppMetricsView toView(String appId, int totalMessageCount) {
            int conversationCount = conversationIds.size();
            return AppMetricsView.builder()
                    .appId(appId)
                    .totalConversations(conversationCount)
                    .totalMessages(totalMessageCount)
                    .userMessages(userMessageCount)
                    .assistantMessages(assistantMessageCount)
                    .avgInteractionsPerConversation(averageUserTurns(conversationCount))
                    .lastActivityAt(mostRecentActivity == null ? null : mostRecentActivity.toString())
                    .build();
        }

        private void countRole(String role) {
            if (USER_ROLE.equalsIgnoreCase(role)) {
                userMessageCount++;
            } else if (ASSISTANT_ROLE.equalsIgnoreCase(role)) {
                assistantMessageCount++;
            }
        }

        private void includeActivityTime(LocalDateTime activityTime) {
            if (activityTime != null
                    && (mostRecentActivity == null || activityTime.isAfter(mostRecentActivity))) {
                mostRecentActivity = activityTime;
            }
        }

        private double averageUserTurns(int conversationCount) {
            if (conversationCount == 0) {
                return 0.0;
            }
            double average = (double) userMessageCount / conversationCount;
            return Math.round(average * 100.0) / 100.0;
        }

        private static LocalDateTime activityTime(AgentMessageEntity message) {
            return message.getUpdatedAt() != null ? message.getUpdatedAt() : message.getCreatedAt();
        }
    }
}
