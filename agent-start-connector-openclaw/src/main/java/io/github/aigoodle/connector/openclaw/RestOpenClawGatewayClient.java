package io.github.aigoodle.connector.openclaw;

import io.github.aigoodle.connector.ConnectorException;
import io.github.aigoodle.connector.channel.ChannelOutboundMessage;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

public class RestOpenClawGatewayClient implements OpenClawGatewayClient {
    private static final String ROOT = "/agent-start-bridge/v1";
    private final RestClient client;
    private final String agentId;

    public RestOpenClawGatewayClient(OpenClawProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory);
        if (properties.getServiceToken() != null && !properties.getServiceToken().isBlank()) {
            builder.defaultHeader("X-Agent-Start-Token", properties.getServiceToken());
        }
        this.client = builder.build();
        this.agentId = properties.getAgentId();
    }

    @Override public OpenClawDtos.RuntimeInfo runtime() {
        return required(client.get().uri(ROOT + "/runtime").retrieve().body(OpenClawDtos.RuntimeInfo.class));
    }
    @Override public List<OpenClawDtos.PluginInfo> plugins() {
        List<OpenClawDtos.PluginInfo> value = client.get().uri(uri -> uri.path(ROOT + "/plugins")
                        .queryParam("agentId", agentId).build()).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return value == null ? List.of() : value;
    }
    @Override public List<OpenClawDtos.ToolInfo> tools() {
        List<OpenClawDtos.ToolInfo> value = client.get().uri(uri -> uri.path(ROOT + "/tools")
                        .queryParam("agentId", agentId).build()).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return value == null ? List.of() : value;
    }
    @Override public OpenClawDtos.InvokeResponse invoke(String toolName, OpenClawDtos.InvokeRequest request) {
        return required(client.post().uri(ROOT + "/tools/{name}/invoke", toolName)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
                .body(OpenClawDtos.InvokeResponse.class));
    }
    @Override public OpenClawDtos.PluginInfo install(OpenClawDtos.InstallRequest request) {
        return required(client.post().uri(ROOT + "/plugins/install")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
                .body(OpenClawDtos.PluginInfo.class));
    }
    @Override public OpenClawDtos.PluginInfo configure(String pluginId, Map<String, Object> config) {
        return required(client.put().uri(ROOT + "/plugins/{id}/config", pluginId)
                .contentType(MediaType.APPLICATION_JSON).body(new OpenClawDtos.ConfigureRequest(config)).retrieve()
                .body(OpenClawDtos.PluginInfo.class));
    }
    @Override public void enable(String pluginId) { postEmpty("/plugins/{id}/enable", pluginId); }
    @Override public void disable(String pluginId) { postEmpty("/plugins/{id}/disable", pluginId); }
    @Override public void uninstall(String pluginId) {
        client.delete().uri(ROOT + "/plugins/{id}", pluginId).retrieve().toBodilessEntity();
    }
    @Override public List<OpenClawDtos.ChannelInfo> channels() {
        List<OpenClawDtos.ChannelInfo> value = client.get().uri(ROOT + "/channels").retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return value == null ? List.of() : value;
    }
    @Override public List<OpenClawDtos.ChannelAccountInfo> channelAccounts(String channelId) {
        List<OpenClawDtos.ChannelAccountInfo> value = client.get()
                .uri(ROOT + "/channels/{channelId}/accounts", channelId).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return value == null ? List.of() : value;
    }
    @Override public OpenClawDtos.ChannelAccountInfo saveChannelAccount(
            String channelId, String accountId, OpenClawDtos.SaveChannelAccountRequest request) {
        return required(client.put().uri(ROOT + "/channels/{channelId}/accounts/{accountId}", channelId, accountId)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
                .body(OpenClawDtos.ChannelAccountInfo.class));
    }
    @Override public OpenClawDtos.ChannelAccountInfo testChannelAccount(String channelId, String accountId) {
        return required(client.post().uri(ROOT + "/channels/{channelId}/accounts/{accountId}/test", channelId, accountId)
                .retrieve().body(OpenClawDtos.ChannelAccountInfo.class));
    }
    @Override public void deleteChannelAccount(String channelId, String accountId) {
        client.delete().uri(ROOT + "/channels/{channelId}/accounts/{accountId}", channelId, accountId)
                .retrieve().toBodilessEntity();
    }
    @Override public void sendChannelMessage(String channelId, String accountId, String targetId, String content) {
        sendChannelMessageWithResult(channelId, accountId, targetId, content);
    }
    @Override public Map<String, Object> sendChannelMessageWithResult(
            String channelId, String accountId, String targetId, String content) {
        return sendChannelMessageWithResult(channelId, accountId, targetId, content, null);
    }
    @Override public Map<String, Object> sendChannelMessageWithResult(
            String channelId, String accountId, String targetId, String content, String idempotencyKey) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("targetId", targetId);
        body.put("content", content);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) body.put("idempotencyKey", idempotencyKey);
        Map<String, Object> response = client.post()
                .uri(ROOT + "/channels/{channelId}/accounts/{accountId}/send", channelId, accountId)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return response == null ? Map.of() : response;
    }

    @Override public Map<String, Object> sendChannelMessageWithResult(ChannelOutboundMessage message) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("targetId", message.targetId());
        body.put("content", message.content());
        body.put("messageType", message.messageType());
        if (!message.attachments().isEmpty()) body.put("attachments", message.attachments());
        if (!message.contentPayload().isEmpty()) body.put("contentPayload", message.contentPayload());
        Object idempotencyKey = message.metadata().get("idempotencyKey");
        if (idempotencyKey != null && !String.valueOf(idempotencyKey).isBlank()) {
            body.put("idempotencyKey", String.valueOf(idempotencyKey));
        }
        Map<String, Object> response = client.post()
                .uri(ROOT + "/channels/{channelId}/accounts/{accountId}/send",
                        message.channelId(), message.accountId())
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return response == null ? Map.of() : response;
    }

    private void postEmpty(String path, String pluginId) {
        client.post().uri(ROOT + path, pluginId).retrieve().toBodilessEntity();
    }
    private static <T> T required(T value) {
        if (value == null) throw new ConnectorException("openclaw_empty_response", "OpenClaw Bridge returned no body");
        return value;
    }
}
