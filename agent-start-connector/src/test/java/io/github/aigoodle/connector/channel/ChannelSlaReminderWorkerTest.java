package io.github.aigoodle.connector.channel;

import io.github.aigoodle.connector.config.ConnectorProperties;
import io.github.aigoodle.connector.persistence.ChannelConversationEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelSlaReminderWorkerTest {
    @Test
    void emitsBreachedReminderAndPersistsStage() {
        ChannelConversationService service = mock(ChannelConversationService.class);
        ChannelSlaNotifier notifier = mock(ChannelSlaNotifier.class);
        ConnectorProperties properties = properties();
        LocalDateTime now = LocalDateTime.of(2026, 8, 19, 12, 0);
        ChannelConversationService.SlaCandidate candidate =
                new ChannelConversationService.SlaCandidate("row-1", "tenant-1", now.minusMinutes(1), 1);
        ChannelConversationEntity row = row(now.minusMinutes(1));
        when(service.findSlaReminderCandidates(now.plusMinutes(5), 25)).thenReturn(List.of(candidate));
        when(service.claimSlaReminder(eq(candidate), eq(2), anyString(), eq(now), eq(Duration.ofSeconds(45))))
                .thenReturn(row);

        new ChannelSlaReminderWorker(service, notifier, properties).poll(now);

        verify(notifier).notify(argThat(reminder -> "BREACHED".equals(reminder.stage())
                && "tenant-1".equals(reminder.tenantId())));
        verify(service).completeSlaReminder(eq("tenant-1"), eq("row-1"), anyString(), eq(2), eq(now));
        verify(service, never()).releaseSlaReminder(anyString(), anyString(), anyString());
    }

    @Test
    void releasesLeaseWhenHostNotifierFails() {
        ChannelConversationService service = mock(ChannelConversationService.class);
        ChannelSlaNotifier notifier = mock(ChannelSlaNotifier.class);
        ConnectorProperties properties = properties();
        LocalDateTime now = LocalDateTime.of(2026, 8, 19, 12, 0);
        ChannelConversationService.SlaCandidate candidate =
                new ChannelConversationService.SlaCandidate("row-1", "tenant-1", now.plusMinutes(2), 0);
        ChannelConversationEntity row = row(now.plusMinutes(2));
        when(service.findSlaReminderCandidates(now.plusMinutes(5), 25)).thenReturn(List.of(candidate));
        when(service.claimSlaReminder(eq(candidate), eq(1), anyString(), eq(now), any())).thenReturn(row);
        doThrow(new IllegalStateException("webhook unavailable")).when(notifier).notify(any());

        new ChannelSlaReminderWorker(service, notifier, properties).poll(now);

        verify(service).releaseSlaReminder(eq("tenant-1"), eq("row-1"), anyString());
        verify(service, never()).completeSlaReminder(anyString(), anyString(), anyString(), anyInt(), any());
    }

    private static ConnectorProperties properties() {
        ConnectorProperties properties = new ConnectorProperties();
        properties.setChannelSlaReminderLeadTime(Duration.ofMinutes(5));
        properties.setChannelSlaReminderLeaseDuration(Duration.ofSeconds(45));
        properties.setChannelSlaReminderBatchSize(25);
        return properties;
    }

    private static ChannelConversationEntity row(LocalDateTime dueAt) {
        ChannelConversationEntity row = new ChannelConversationEntity();
        row.setId("row-1"); row.setTenantId("tenant-1"); row.setConnectionId("connection-1");
        row.setConversationId("conversation-1"); row.setProvider("openclaw"); row.setAccountId("qq-1");
        row.setStatus("WAITING_HUMAN"); row.setSlaDueAt(dueAt); row.setUnreadCount(3);
        return row;
    }
}
