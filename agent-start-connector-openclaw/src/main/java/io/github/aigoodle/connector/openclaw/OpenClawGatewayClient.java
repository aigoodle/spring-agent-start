package io.github.aigoodle.connector.openclaw;

import io.github.aigoodle.connector.channel.ChannelOutboundMessage;
import java.util.List;
import java.util.Map;

public interface OpenClawGatewayClient {
    OpenClawDtos.RuntimeInfo runtime();
    List<OpenClawDtos.PluginInfo> plugins();
    List<OpenClawDtos.ToolInfo> tools();
    OpenClawDtos.InvokeResponse invoke(String toolName, OpenClawDtos.InvokeRequest request);
    OpenClawDtos.PluginInfo install(OpenClawDtos.InstallRequest request);
    OpenClawDtos.PluginInfo configure(String pluginId, Map<String, Object> config);
    void enable(String pluginId);
    void disable(String pluginId);
    void uninstall(String pluginId);
    default List<OpenClawDtos.ChannelInfo> channels() { return List.of(); }
    default List<OpenClawDtos.ChannelAccountInfo> channelAccounts(String channelId) { return List.of(); }
    default OpenClawDtos.ChannelAccountInfo saveChannelAccount(String channelId, String accountId,
                                                                OpenClawDtos.SaveChannelAccountRequest request) {
        throw new UnsupportedOperationException("channel accounts are not supported");
    }
    default OpenClawDtos.ChannelAccountInfo testChannelAccount(String channelId, String accountId) {
        throw new UnsupportedOperationException("channel account tests are not supported");
    }
    default void deleteChannelAccount(String channelId, String accountId) {
        throw new UnsupportedOperationException("channel accounts are not supported");
    }
    default void sendChannelMessage(String channelId, String accountId, String targetId, String content) {
        throw new UnsupportedOperationException("channel outbound is not supported");
    }
    default Map<String, Object> sendChannelMessageWithResult(String channelId, String accountId,
                                                             String targetId, String content) {
        sendChannelMessage(channelId, accountId, targetId, content);
        return Map.of();
    }
    default Map<String, Object> sendChannelMessageWithResult(String channelId, String accountId,
                                                             String targetId, String content,
                                                             String idempotencyKey) {
        return sendChannelMessageWithResult(channelId, accountId, targetId, content);
    }
    default Map<String, Object> sendChannelMessageWithResult(ChannelOutboundMessage message) {
        Object value = message.metadata().get("idempotencyKey");
        String key = value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
        return sendChannelMessageWithResult(message.channelId(), message.accountId(), message.targetId(),
                message.content(), key);
    }
}
