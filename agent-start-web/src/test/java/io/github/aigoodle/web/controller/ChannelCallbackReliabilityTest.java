package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.channel.ChannelEventLogService;
import io.github.aigoodle.connector.channel.ChannelInboundDispatcher;
import io.github.aigoodle.connector.channel.ChannelInboundEvent;
import io.github.aigoodle.connector.channel.ChannelInboundResult;
import io.github.aigoodle.connector.hermes.HermesProperties;
import io.github.aigoodle.connector.openclaw.OpenClawProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelCallbackReliabilityTest {
    @Test
    void openClawAcknowledgesAgentReplyWithoutReturningTextForDirectDelivery() {
        ChannelInboundDispatcher dispatcher = mock(ChannelInboundDispatcher.class);
        ChannelEventLogService events = mock(ChannelEventLogService.class);
        OpenClawProperties properties = new OpenClawProperties(); properties.setServiceToken("token");
        ChannelInboundEvent inbound = inbound("openclaw");
        ChannelEventLogService.InboundClaim claim = acquiredClaim();
        when(events.claimInbound(eq(inbound), anyString())).thenReturn(claim);
        when(dispatcher.dispatch(inbound)).thenReturn(ChannelInboundResult.reply("可靠回复", Map.of("managed", true)));

        ChannelInboundResult acknowledgement = new ChannelEventController(dispatcher, properties, events)
                .receive("token", inbound);

        assertThat(acknowledgement.handled()).isTrue();
        assertThat(acknowledgement.reply()).isNull();
        assertThat(acknowledgement.metadata()).containsEntry("replyQueued", true);
        verify(events).completeInbound(eq(claim), eq(inbound),
                argThat(result -> "可靠回复".equals(result.reply())), anyLong(), isNull());
    }

    @Test
    void persistenceFailurePreventsOpenClawAcknowledgementSoRuntimeCanRetry() {
        ChannelInboundDispatcher dispatcher = mock(ChannelInboundDispatcher.class);
        ChannelEventLogService events = mock(ChannelEventLogService.class);
        OpenClawProperties properties = new OpenClawProperties(); properties.setServiceToken("token");
        ChannelInboundEvent inbound = inbound("openclaw");
        ChannelEventLogService.InboundClaim claim = acquiredClaim();
        when(events.claimInbound(eq(inbound), anyString())).thenReturn(claim);
        when(dispatcher.dispatch(inbound)).thenReturn(ChannelInboundResult.reply("回复", Map.of("managed", true)));
        when(events.completeInbound(eq(claim), eq(inbound), any(), anyLong(), isNull()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> new ChannelEventController(dispatcher, properties, events).receive("token", inbound))
                .isInstanceOf(IllegalStateException.class).hasMessage("db down");
    }

    @Test
    void hermesUsesTheSameQueuedReplyContract() {
        ChannelInboundDispatcher dispatcher = mock(ChannelInboundDispatcher.class);
        ChannelEventLogService events = mock(ChannelEventLogService.class);
        HermesProperties properties = new HermesProperties(); properties.setBridgeToken("token");
        ChannelInboundEvent inbound = inbound("hermes");
        ChannelEventLogService.InboundClaim claim = acquiredClaim();
        when(events.claimInbound(eq(inbound), anyString())).thenReturn(claim);
        when(dispatcher.dispatch(inbound)).thenReturn(ChannelInboundResult.reply("可靠回复", Map.of("managed", true)));

        ChannelInboundResult acknowledgement = new HermesChannelEventController(dispatcher, events, properties)
                .receive("token", inbound);

        assertThat(acknowledgement.reply()).isNull();
        assertThat(acknowledgement.metadata()).containsEntry("replyQueued", true);
        verify(events).completeInbound(eq(claim), eq(inbound), any(), anyLong(), isNull());
    }

    @Test
    void concurrentDuplicateIsRetriedWithoutExecutingAgentTwice() {
        ChannelInboundDispatcher dispatcher = mock(ChannelInboundDispatcher.class);
        ChannelEventLogService events = mock(ChannelEventLogService.class);
        OpenClawProperties properties = new OpenClawProperties(); properties.setServiceToken("token");
        ChannelInboundEvent inbound = inbound("openclaw");
        when(events.claimInbound(eq(inbound), anyString())).thenReturn(
                new ChannelEventLogService.InboundClaim("event-1", "tenant-1", null, false, null));

        assertThatThrownBy(() -> new ChannelEventController(dispatcher, properties, events)
                .receive("token", inbound))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503 SERVICE_UNAVAILABLE");
        verifyNoInteractions(dispatcher);
    }

    @Test
    void callbackWithoutPlatformMessageIdIsRejectedBeforeAgentExecution() {
        ChannelInboundDispatcher dispatcher = mock(ChannelInboundDispatcher.class);
        ChannelEventLogService events = mock(ChannelEventLogService.class);
        OpenClawProperties properties = new OpenClawProperties(); properties.setServiceToken("token");
        ChannelInboundEvent missingId = new ChannelInboundEvent("openclaw", "qqbot", "account-1", null,
                "user-1", "conversation-1", "你好", Instant.now(), false, Map.of());

        assertThatThrownBy(() -> new ChannelEventController(dispatcher, properties, events)
                .receive("token", missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("messageId is required");
        verifyNoInteractions(dispatcher, events);
    }

    @Test
    void authenticatedDeliveryReceiptUsesTheBoundRuntimeAccountScope() {
        ChannelInboundDispatcher dispatcher = mock(ChannelInboundDispatcher.class);
        ChannelEventLogService events = mock(ChannelEventLogService.class);
        OpenClawProperties properties = new OpenClawProperties(); properties.setServiceToken("token");
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 8, 20, 2, 0);
        when(events.markDelivered("openclaw", "qqbot", "account-1", "platform-9", deliveredAt))
                .thenReturn(true);

        Map<String, Boolean> result = new ChannelEventController(dispatcher, properties, events)
                .delivery("token", new ChannelEventController.DeliveryReceipt(
                        "openclaw", "qqbot", "account-1", "platform-9", deliveredAt));

        assertThat(result).containsEntry("updated", true);
        verify(events).markDelivered("openclaw", "qqbot", "account-1", "platform-9", deliveredAt);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void deliveryReceiptRejectsAnInvalidRuntimeTokenBeforePersistence() {
        ChannelEventLogService events = mock(ChannelEventLogService.class);
        OpenClawProperties properties = new OpenClawProperties(); properties.setServiceToken("token");
        ChannelEventController controller = new ChannelEventController(
                mock(ChannelInboundDispatcher.class), properties, events);

        assertThatThrownBy(() -> controller.delivery("wrong",
                new ChannelEventController.DeliveryReceipt(
                        "openclaw", "qqbot", "account-1", "platform-9", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
        verifyNoInteractions(events);
    }

    private static ChannelInboundEvent inbound(String provider) {
        return new ChannelInboundEvent(provider, "qqbot", "account-1", "message-1", "user-1",
                "conversation-1", "你好", Instant.now(), false, Map.of());
    }

    private static ChannelEventLogService.InboundClaim acquiredClaim() {
        return new ChannelEventLogService.InboundClaim("event-1", "tenant-1", "worker-1", true, null);
    }
}
