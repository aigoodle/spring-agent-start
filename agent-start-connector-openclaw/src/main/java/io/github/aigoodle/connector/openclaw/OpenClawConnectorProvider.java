package io.github.aigoodle.connector.openclaw;

import io.github.aigoodle.connector.*;
import io.github.aigoodle.connector.execution.*;
import io.github.aigoodle.connector.provider.ConnectorProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps the OpenClaw Gateway tool catalog onto the provider-neutral connector SPI. */
public class OpenClawConnectorProvider implements ConnectorProvider {
    private static final String PROVIDER = "openclaw";
    private final OpenClawGatewayClient client;
    private volatile List<ConnectorDefinition> cachedDefinitions = List.of();

    public OpenClawConnectorProvider(OpenClawGatewayClient client) { this.client = client; }
    @Override public String type() { return PROVIDER; }

    @Override
    public List<ConnectorDefinition> discover() {
        try {
            List<ConnectorDefinition> discovered = discoverAvailable();
            cachedDefinitions = List.copyOf(discovered);
            return discovered;
        } catch (RuntimeException unavailable) {
            // OpenClaw is an optional sidecar. Keep the Java host bootable and retain the last
            // successful catalog while the gateway is restarting or temporarily unavailable.
            return cachedDefinitions;
        }
    }

    private List<ConnectorDefinition> discoverAvailable() {
        Map<String, OpenClawDtos.PluginInfo> plugins = new LinkedHashMap<>();
        for (OpenClawDtos.PluginInfo plugin : client.plugins()) plugins.put(plugin.id(), plugin);
        Map<String, List<OpenClawDtos.ToolInfo>> toolsByPlugin = new LinkedHashMap<>();
        for (OpenClawDtos.ToolInfo tool : client.tools()) {
            toolsByPlugin.computeIfAbsent(tool.pluginId(), ignored -> new ArrayList<>()).add(tool);
        }
        List<ConnectorDefinition> definitions = new ArrayList<>();
        for (Map.Entry<String, List<OpenClawDtos.ToolInfo>> entry : toolsByPlugin.entrySet()) {
            OpenClawDtos.PluginInfo plugin = plugins.get(entry.getKey());
            List<ConnectorActionDefinition> actions = entry.getValue().stream().map(this::action).toList();
            Map<String, Object> connectorMetadata = new LinkedHashMap<>();
            if (plugin != null && plugin.metadata() != null) connectorMetadata.putAll(plugin.metadata());
            connectorMetadata.putIfAbsent("capabilities", inferCapabilities(plugin, entry.getValue()));
            definitions.add(new ConnectorDefinition(new ConnectorKey(PROVIDER, entry.getKey()),
                    plugin == null ? entry.getKey() : plugin.name(),
                    plugin == null ? "OpenClaw plugin" : plugin.description(),
                    plugin == null ? "unknown" : plugin.version(), ConnectorSource.OPENCLAW,
                    null, "openclaw", plugin == null ? null : plugin.configSchema(), actions,
                    ConnectorTrustLevel.REVIEWED, plugin == null ? null : plugin.license(), connectorMetadata));
        }
        return definitions;
    }

    private static List<String> inferCapabilities(OpenClawDtos.PluginInfo plugin,
                                                   List<OpenClawDtos.ToolInfo> tools) {
        if (plugin != null && plugin.metadata() != null
                && plugin.metadata().get("capabilities") instanceof List<?> declared && !declared.isEmpty()) {
            return declared.stream().map(String::valueOf).toList();
        }
        boolean channel = tools.stream().anyMatch(tool -> {
            String value = (tool.name() + " " + tool.label() + " " + tool.tags()).toLowerCase();
            return value.contains("channel") || value.contains("message") || value.contains("send");
        });
        return channel ? List.of("CHANNEL_INBOUND", "CHANNEL_OUTBOUND", "ACTION") : List.of("ACTION");
    }

    @Override
    public ConnectorResult execute(ConnectorExecutionRequest request) {
        OpenClawDtos.InvokeResponse response = client.invoke(request.actionId(),
                new OpenClawDtos.InvokeRequest(request.context().executionId(), null,
                        request.arguments(), contextMap(request.context())));
        if (!response.success()) {
            OpenClawDtos.InvokeResponse.Error error = response.error();
            return ConnectorResult.failure(error == null ? "openclaw_error" : error.code(),
                    error == null ? "OpenClaw tool invocation failed" : error.message(),
                    error != null && error.retryable());
        }
        List<ConnectorContent> content = mapContent(response.content());
        return new ConnectorResult(true, response.data(), content, null,
                response.metadata() == null ? Map.of() : response.metadata());
    }

    private ConnectorActionDefinition action(OpenClawDtos.ToolInfo tool) {
        ConnectorRiskLevel risk = switch (tool.risk() == null ? "" : tool.risk().toLowerCase()) {
            case "read", "low" -> ConnectorRiskLevel.READ;
            case "destructive", "high" -> ConnectorRiskLevel.DESTRUCTIVE;
            default -> ConnectorRiskLevel.WRITE;
        };
        return new ConnectorActionDefinition(tool.name(), tool.label(), tool.description(),
                tool.inputSchema(), null, false, Duration.ofSeconds(60), risk,
                tool.metadata() == null ? Map.of() : tool.metadata());
    }

    private static Map<String, Object> contextMap(ConnectorExecutionContext context) {
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "tenantId", context.tenantId()); put(values, "userId", context.userId());
        put(values, "agentId", context.agentId()); put(values, "workflowId", context.workflowId());
        put(values, "runId", context.runId()); put(values, "nodeId", context.nodeId());
        values.putAll(context.attributes());
        return values;
    }
    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
    private static List<ConnectorContent> mapContent(List<Map<String, Object>> source) {
        if (source == null) return List.of();
        return source.stream().map(item -> new ConnectorContent(
                parseType(String.valueOf(item.getOrDefault("type", "TEXT"))),
                item.get("text") == null ? null : String.valueOf(item.get("text")),
                item.get("uri") == null ? null : String.valueOf(item.get("uri")),
                item.get("mediaType") == null ? null : String.valueOf(item.get("mediaType")), item)).toList();
    }
    private static ConnectorContent.Type parseType(String value) {
        try { return ConnectorContent.Type.valueOf(value.toUpperCase()); }
        catch (IllegalArgumentException ignored) { return ConnectorContent.Type.RESOURCE; }
    }
}
