package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppMetricsServiceTest {

    @Test
    void summarizesAnApplicationWithoutMessages() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        AppMetricsService metricsService = new AppMetricsService(messageMapper);

        AppMetricsView metrics = metricsService.summarize("app-1");

        assertThat(metrics.getAppId()).isEqualTo("app-1");
        assertThat(metrics.getTotalConversations()).isZero();
        assertThat(metrics.getTotalMessages()).isZero();
        assertThat(metrics.getAvgInteractionsPerConversation()).isZero();
        assertThat(metrics.getLastActivityAt()).isNull();
    }

    @Test
    void countsRolesAndDistinctConversations() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("conversation-1", "user"),
                message("conversation-1", "ASSISTANT"),
                message("conversation-2", "USER"),
                message("conversation-2", "TOOL"),
                message(null, null)));
        AppMetricsService metricsService = new AppMetricsService(messageMapper);

        AppMetricsView metrics = metricsService.summarize("app-1");

        assertThat(metrics.getTotalConversations()).isEqualTo(2);
        assertThat(metrics.getTotalMessages()).isEqualTo(5);
        assertThat(metrics.getUserMessages()).isEqualTo(2);
        assertThat(metrics.getAssistantMessages()).isOne();
        assertThat(metrics.getAvgInteractionsPerConversation()).isEqualTo(1.0);
    }

    @Test
    void roundsAverageUserTurnsToTwoDecimalPlaces() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("conversation-1", "USER"),
                message("conversation-2", "USER"),
                message("conversation-3", "USER"),
                message("conversation-3", "USER")));
        AppMetricsService metricsService = new AppMetricsService(messageMapper);

        AppMetricsView metrics = metricsService.summarize("app-1");

        assertThat(metrics.getAvgInteractionsPerConversation()).isEqualTo(1.33);
    }

    @Test
    void usesCreationTimeWhenUpdateTimeIsUnavailable() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentMessageEntity olderUpdatedMessage = message("conversation-1", "USER");
        olderUpdatedMessage.setUpdatedAt(LocalDateTime.parse("2026-01-01T10:00:00"));
        AgentMessageEntity newerCreatedMessage = message("conversation-1", "ASSISTANT");
        newerCreatedMessage.setCreatedAt(LocalDateTime.parse("2026-01-02T11:30:00"));
        when(messageMapper.selectList(any())).thenReturn(
                List.of(olderUpdatedMessage, newerCreatedMessage));
        AppMetricsService metricsService = new AppMetricsService(messageMapper);

        AppMetricsView metrics = metricsService.summarize("app-1");

        assertThat(metrics.getLastActivityAt()).isEqualTo("2026-01-02T11:30");
    }

    private static AgentMessageEntity message(String conversationId, String role) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setConversationId(conversationId);
        message.setRole(role);
        return message;
    }
}
