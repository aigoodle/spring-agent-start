package io.github.aigoodle.connector.openclaw;

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
}
