package io.github.aigoodle.web.service;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.connector.channel.ChannelConnectionService;
import io.github.aigoodle.connector.channel.ChannelInboundEvent;
import io.github.aigoodle.connector.channel.ChannelInboundResult;
import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.service.TriggerInvocationRequest;
import io.github.aigoodle.trigger.service.TriggerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelWorkflowInboundHandlerTest {
    @Test
    void selectedMessageConnectorExecutesWorkflowWithAccountIdentityTriggerObject() {
        TriggerService triggers = mock(TriggerService.class);
        ChannelConnectionService connections = mock(ChannelConnectionService.class);
        ChannelWorkflowInboundHandler handler = new ChannelWorkflowInboundHandler(triggers, connections);
        ChannelInboundEvent event = new ChannelInboundEvent("openclaw", "qqbot", "runtime-account-1",
                "message-1", "external-user-1", "conversation-1", "你好", "TEXT", List.of(), Map.of(),
                Instant.parse("2026-08-21T08:00:00Z"), false, Map.of("replyTargetId", "external-user-1"),
                "openclaw-default");
        ChannelConnectionService.Ownership ownership = new ChannelConnectionService.Ownership(
                "connection-1", "tenant-1", "user-1", "openclaw-default");
        ChannelConnectionService.View connection = new ChannelConnectionService.View(
                "connection-1", "tenant-1", "USER", "user-1", "openclaw", "qqbot", "招生机器人",
                "ACTIVE", "ONLINE", "runtime-account-1", null, null, "openclaw-default", Map.of(),
                true, null, null, 1L);
        TriggerEntity trigger = new TriggerEntity();
        trigger.setId("trigger-1"); trigger.setTenantId("tenant-1"); trigger.setType(TriggerType.CHANNEL_MESSAGE);
        trigger.setTargetType("workflow"); trigger.setTargetId("workflow-1"); trigger.setEnabled(true);
        trigger.setConfigJson(JsonUtils.toJson(Map.of("provider", "openclaw", "channelId", "qqbot",
                "channelName", "QQ Bot", "connectionId", "connection-1", "messageTypes", List.of("TEXT"))));
        when(connections.ownership("openclaw", "openclaw-default", "qqbot", "runtime-account-1"))
                .thenReturn(ownership);
        when(connections.get("connection-1", "tenant-1")).thenReturn(connection);
        when(triggers.listEnabledByType("tenant-1", TriggerType.CHANNEL_MESSAGE)).thenReturn(List.of(trigger));
        when(triggers.config(trigger)).thenReturn(JsonUtils.parseMap(trigger.getConfigJson()));
        when(triggers.fireAsynchronouslyAs(eq("tenant-1"), eq("user-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn("invocation-1");

        ChannelInboundResult result = handler.tryHandle(event).orElseThrow();

        assertThat(result.handled()).isTrue();
        assertThat(result.reply()).isNull();
        assertThat(result.metadata()).containsEntry("triggerInvocationId", "invocation-1")
                .containsEntry("routeSource", "WORKFLOW_TRIGGER");
        ArgumentCaptor<TriggerInvocationRequest> request = ArgumentCaptor.forClass(TriggerInvocationRequest.class);
        verify(triggers).fireAsynchronouslyAs(eq("tenant-1"), eq("user-1"), request.capture());
        assertThat(request.getValue().conversationId())
                .isEqualTo("channel:tenant-1:connection-1:conversation-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeTrigger = (Map<String, Object>) request.getValue().payload().get("triggers");
        assertThat(runtimeTrigger).containsEntry("channelId", "qqbot")
                .containsEntry("channelName", "QQ Bot")
                .containsEntry("instanceName", "招生机器人")
                .containsEntry("accountId", "runtime-account-1")
                .doesNotContainKeys("senderId", "replyTargetId", "conversationId", "messageId", "messageType", "group",
                        "tenantId", "userId", "ownerId", "ownerType");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeMessage = (Map<String, Object>) request.getValue().payload().get("message");
        assertThat(runtimeMessage).containsEntry("senderId", "external-user-1")
                .containsEntry("replyTargetId", "external-user-1")
                .containsEntry("conversationId", "conversation-1")
                .containsEntry("tenantId", "tenant-1")
                .containsEntry("userId", "user-1")
                .containsEntry("ownerId", "user-1")
                .containsEntry("ownerType", "USER")
                .containsEntry("id", "message-1")
                .containsEntry("type", "TEXT");
        assertThat(request.getValue().payload())
                .containsEntry("_channel_conversation", true)
                .containsEntry("_conversation_sender_id", "external-user-1");
    }
}
