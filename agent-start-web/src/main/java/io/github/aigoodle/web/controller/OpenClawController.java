package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.openclaw.OpenClawDtos;
import io.github.aigoodle.connector.openclaw.OpenClawGatewayClient;
import io.github.aigoodle.connector.channel.ChannelCatalogService;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.support.ChannelRuntimeAdministrationPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** OpenClaw runtime and plugin lifecycle facade; the browser never talks to Bridge directly. */
@RestController
@ConditionalOnBean(OpenClawGatewayClient.class)
@RequestMapping("/openclaw")
public class OpenClawController {
    private final OpenClawGatewayClient client;
    private final ConnectorRegistry connectors;
    private final ObjectProvider<ToolRegistry> tools;
    private final ObjectProvider<ChannelCatalogService> channelCatalog;
    private final ChannelRuntimeAdministrationPolicy administration;

    public OpenClawController(OpenClawGatewayClient client, ConnectorRegistry connectors,
                              ObjectProvider<ToolRegistry> tools,
                              ObjectProvider<ChannelCatalogService> channelCatalog,
                              ChannelRuntimeAdministrationPolicy administration) {
        this.client = client;
        this.connectors = connectors;
        this.tools = tools;
        this.channelCatalog = channelCatalog;
        this.administration = administration;
    }

    @GetMapping("/runtime") public ApiResponse<OpenClawDtos.RuntimeInfo> runtime() {
        return ApiResponse.ok(client.runtime());
    }
    @GetMapping("/plugins") public ApiResponse<List<OpenClawDtos.PluginInfo>> plugins() {
        return ApiResponse.ok(client.plugins());
    }
    @GetMapping("/tools") public ApiResponse<List<OpenClawDtos.ToolInfo>> openClawTools() {
        return ApiResponse.ok(client.tools());
    }
    @PostMapping("/plugins/install")
    public ApiResponse<OpenClawDtos.PluginInfo> install(@RequestBody OpenClawDtos.InstallRequest request) {
        return ApiResponse.ok(installPlugin(request));
    }

    public OpenClawDtos.PluginInfo installPlugin(OpenClawDtos.InstallRequest request) {
        administration.requireRuntimeAdministrator();
        OpenClawDtos.PluginInfo installed = client.install(request); refresh(); return installed;
    }
    @PutMapping("/plugins/{pluginId}/config")
    public ApiResponse<OpenClawDtos.PluginInfo> configure(@PathVariable String pluginId,
                                                         @RequestBody Map<String, Object> config) {
        administration.requireRuntimeAdministrator();
        OpenClawDtos.PluginInfo plugin = client.configure(pluginId, config); refresh(); return ApiResponse.ok(plugin);
    }
    @PostMapping("/plugins/{pluginId}/enable")
    public ApiResponse<Void> enable(@PathVariable String pluginId) { administration.requireRuntimeAdministrator(); client.enable(pluginId); refresh(); return ApiResponse.ok(null); }
    @PostMapping("/plugins/{pluginId}/disable")
    public ApiResponse<Void> disable(@PathVariable String pluginId) { administration.requireRuntimeAdministrator(); client.disable(pluginId); refresh(); return ApiResponse.ok(null); }
    @DeleteMapping("/plugins/{pluginId}")
    public ApiResponse<Void> uninstall(@PathVariable String pluginId) { administration.requireRuntimeAdministrator(); client.uninstall(pluginId); refresh(); return ApiResponse.ok(null); }

    private void refresh() {
        connectors.refresh();
        ToolRegistry registry = tools.getIfAvailable();
        if (registry != null) registry.refresh();
        ChannelCatalogService catalog = channelCatalog.getIfAvailable();
        if (catalog != null) catalog.invalidate();
    }
}
