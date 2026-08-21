package io.github.aigoodle.connector.openclaw;

import io.github.aigoodle.connector.channel.ChannelAccount;
import io.github.aigoodle.connector.channel.SaveChannelAccountRequest;
import io.github.aigoodle.connector.channel.ChannelOutboundMessage;
import io.github.aigoodle.connector.channel.ChannelSendResult;
import io.github.aigoodle.connector.channel.ChannelAttachment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OpenClawChannelRuntimeProviderTest {
    @Test
    void mapsGenericAccountSaveToOpenClawBridge() {
        OpenClawGatewayClient client = mock(OpenClawGatewayClient.class);
        when(client.saveChannelAccount(eq("qqbot"), eq("account-1"), any())).thenReturn(
                new OpenClawDtos.ChannelAccountInfo("qqbot", "account-1", "QQ 客服", true,
                        true, true, false, null, "connecting", Map.of()));
        OpenClawChannelRuntimeProvider provider = new OpenClawChannelRuntimeProvider(client);

        ChannelAccount result = provider.saveAccount(new SaveChannelAccountRequest(
                "qqbot", "account-1", "QQ 客服", true, Map.of("appId", "app")));

        ArgumentCaptor<OpenClawDtos.SaveChannelAccountRequest> body =
                ArgumentCaptor.forClass(OpenClawDtos.SaveChannelAccountRequest.class);
        verify(client).saveChannelAccount(eq("qqbot"), eq("account-1"), body.capture());
        assertThat(body.getValue().config()).containsEntry("appId", "app");
        assertThat(result.running()).isTrue();
        assertThat(result.lastError()).isEqualTo("connecting");
    }

    @Test
    void sendsGenericOutboundMessageThroughBridge() {
        OpenClawGatewayClient client = mock(OpenClawGatewayClient.class);
        when(client.sendChannelMessageWithResult(any(ChannelOutboundMessage.class)))
                .thenReturn(Map.of());
        OpenClawChannelRuntimeProvider provider = new OpenClawChannelRuntimeProvider(client);
        provider.send(new ChannelOutboundMessage("qqbot", "account-1", "user-1", "conversation-1", "你好", Map.of()));
        verify(client).sendChannelMessageWithResult(any(ChannelOutboundMessage.class));
    }

    @Test
    void exposesPlatformMessageIdFromBridgeAcknowledgement() {
        OpenClawGatewayClient client = mock(OpenClawGatewayClient.class);
        when(client.sendChannelMessageWithResult(any(ChannelOutboundMessage.class)))
                .thenReturn(Map.of("messageId", "qq-message-9"));
        OpenClawChannelRuntimeProvider provider = new OpenClawChannelRuntimeProvider(client);

        ChannelSendResult result = provider.sendWithResult(new ChannelOutboundMessage(
                "qqbot", "account-1", "user-1", "conversation-1", "你好", Map.of()));

        assertThat(result.platformMessageId()).isEqualTo("qq-message-9");
    }

    @Test
    void findsPlatformMessageIdInNestedBridgeResult() {
        OpenClawGatewayClient client = mock(OpenClawGatewayClient.class);
        when(client.sendChannelMessageWithResult(any(ChannelOutboundMessage.class)))
                .thenReturn(Map.of("result", Map.of("message_id", "nested-message-2")));

        ChannelSendResult result = new OpenClawChannelRuntimeProvider(client).sendWithResult(
                new ChannelOutboundMessage("qqbot", "account-1", "user-1", null, "你好", Map.of()));

        assertThat(result.platformMessageId()).isEqualTo("nested-message-2");
    }

    @Test
    void forwardsOutboxIdempotencyKeyToBridge() {
        OpenClawGatewayClient client = mock(OpenClawGatewayClient.class);
        when(client.sendChannelMessageWithResult(any(ChannelOutboundMessage.class)))
                .thenReturn(Map.of("messageId", "message-1"));

        new OpenClawChannelRuntimeProvider(client).sendWithResult(new ChannelOutboundMessage(
                "qqbot", "account-1", "user-1", "conversation-1", "你好",
                Map.of("idempotencyKey", "outbox-7")));

        ArgumentCaptor<ChannelOutboundMessage> sent = ArgumentCaptor.forClass(ChannelOutboundMessage.class);
        verify(client).sendChannelMessageWithResult(sent.capture());
        assertThat(sent.getValue().metadata()).containsEntry("idempotencyKey", "outbox-7");
    }

    @Test
    void preservesRichOutboundEnvelopeForBridge() {
        OpenClawGatewayClient client = mock(OpenClawGatewayClient.class);
        when(client.sendChannelMessageWithResult(any(ChannelOutboundMessage.class))).thenReturn(Map.of());
        ChannelOutboundMessage message = new ChannelOutboundMessage("qqbot", "account-1", "user-1",
                "conversation-1", "请查看", "IMAGE",
                List.of(new ChannelAttachment("IMAGE", "https://example.test/a.png", "a.png",
                        "image/png", 12L, Map.of())), Map.of("text", "卡片"), Map.of());

        new OpenClawChannelRuntimeProvider(client).sendWithResult(message);

        verify(client).sendChannelMessageWithResult(message);
    }

    @Test
    void discoversInstallableChannelWhenBridgeMetadataContainsNullValues() {
        OpenClawGatewayClient client = mock(OpenClawGatewayClient.class);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("origin", "installable"); metadata.put("systemImage", null); metadata.put("pluginStatus", null);
        when(client.channels()).thenReturn(List.of(new OpenClawDtos.ChannelInfo(
                "feishu", "Feishu", "Feishu channel", null, false, false, "NOT_INSTALLED",
                "{}", "{}", Map.of(), Map.of("multiAccount", true), metadata)));

        var channels = new OpenClawChannelRuntimeProvider(client).discoverChannels();

        assertThat(channels).hasSize(1);
        assertThat(channels.getFirst().metadata()).containsEntry("origin", "installable")
                .doesNotContainKeys("systemImage", "pluginStatus");
    }

    @Test
    void preservesExplicitReceiptCapabilitiesFromBridge() {
        OpenClawGatewayClient client = mock(OpenClawGatewayClient.class);
        when(client.channels()).thenReturn(List.of(new OpenClawDtos.ChannelInfo(
                "qqbot", "QQ Bot", "QQ channel", "1", true, true, "ONLINE",
                "{}", "{}", Map.of(), Map.of(
                        "multiAccount", true,
                        "inbound", true,
                        "outbound", true,
                        "deliveryReceipts", false,
                        "readReceipts", false), Map.of())));

        var channel = new OpenClawChannelRuntimeProvider(client).discoverChannels().getFirst();

        assertThat(channel.capabilities())
                .containsEntry("outbound", true)
                .containsEntry("deliveryReceipts", false)
                .containsEntry("readReceipts", false);
    }
}
