package io.github.aigoodle.connector.openclaw;

import io.github.aigoodle.connector.ConnectorException;
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

    private void postEmpty(String path, String pluginId) {
        client.post().uri(ROOT + path, pluginId).retrieve().toBodilessEntity();
    }
    private static <T> T required(T value) {
        if (value == null) throw new ConnectorException("openclaw_empty_response", "OpenClaw Bridge returned no body");
        return value;
    }
}
