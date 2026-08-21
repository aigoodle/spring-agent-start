package io.github.aigoodle.connector.openclaw;

import java.util.List;
import java.util.Map;
import java.time.Instant;

public final class OpenClawDtos {
    private OpenClawDtos() {}

    public record RuntimeInfo(String status, String version, String bridgeVersion, Instant startedAt,
                              Map<String, Object> inbound, List<String> capabilities) {}
    public record PluginInfo(String id, String name, String version, String description,
                             boolean enabled, String license, String configSchema,
                             Map<String, Object> metadata) {}
    public record ToolInfo(String pluginId, String name, String label, String description,
                           String inputSchema, String risk, List<String> tags,
                           Map<String, Object> metadata) {}
    public record InvokeRequest(String requestId, String agentId, Map<String, Object> arguments,
                                Map<String, Object> context) {}
    public record InvokeResponse(boolean success, String toolName, Object data,
                                 List<Map<String, Object>> content, Error error,
                                 Map<String, Object> metadata) {
        public record Error(String code, String message, boolean retryable) {}
    }
    public record InstallRequest(String sourceType, String source, String version) {}
    public record ConfigureRequest(Map<String, Object> config) {}
    public record ChannelInfo(String id, String label, String description, String version,
                              boolean installed, boolean enabled, String runtimeStatus,
                              String credentialSchema, String configSchema,
                              Map<String, Object> uiSchema, Map<String, Object> capabilities,
                              Map<String, Object> metadata) {}
    public record ChannelAccountInfo(String channelId, String accountId, String name,
                                     boolean enabled, boolean configured, boolean running,
                                     boolean connected, Instant lastConnectedAt, String lastError,
                                     Map<String, Object> metadata) {}
    public record SaveChannelAccountRequest(String name, boolean enabled,
                                            Map<String, Object> config) {}
}
