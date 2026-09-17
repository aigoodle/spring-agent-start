package io.github.aigoodle.web.controller;

import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.tool.mcp.McpClientManager;
import io.github.aigoodle.tool.mcp.McpProperties;
import io.github.aigoodle.tool.mcp.McpToolProvider;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Runtime MCP catalog. Secrets are accepted for stdio child-process env and never returned. */
@RestController
@ConditionalOnClass(McpClientManager.class)
@RequestMapping("/mcp-servers")
public class McpServerController {
    private final ObjectProvider<McpClientManager> clients;
    private final ObjectProvider<McpToolProvider> provider;
    private final ObjectProvider<ToolRegistry> tools;

    public McpServerController(ObjectProvider<McpClientManager> clients,
                               ObjectProvider<McpToolProvider> provider,
                               ObjectProvider<ToolRegistry> tools) {
        this.clients = clients; this.provider = provider; this.tools = tools;
    }

    public record ServerView(String id, String name, String transport, boolean enabled, String url,
                             String command, List<String> args, boolean envConfigured,
                             String status, Integer toolCount, String lastError) {}
    public record SaveServer(String id, String name, String transport, Boolean enabled, String url,
                             String command, List<String> args, Map<String, String> env) {}

    @GetMapping
    public ApiResponse<List<ServerView>> list() {
        return ApiResponse.ok(clientManager().servers().stream().map(this::view).toList());
    }

    @PostMapping
    public ApiResponse<ServerView> save(@RequestBody SaveServer request) {
        McpClientManager clients = clientManager();
        if (request.id() != null && !request.id().isBlank() && !request.id().equals(request.name())) clients.remove(request.id());
        McpProperties.Server server = new McpProperties.Server();
        server.setName(request.name()); server.setType(request.transport() == null ? "stdio" : request.transport().toLowerCase());
        server.setEnabled(request.enabled() == null || request.enabled()); server.setUrl(request.url());
        server.setCommand(request.command()); server.setArgs(request.args() == null ? List.of() : request.args());
        server.setEnv(request.env() == null ? Map.of() : request.env());
        clients.upsert(server); refreshTools();
        return ApiResponse.ok(view(server));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) { clientManager().remove(id); refreshTools(); return ApiResponse.ok(null); }

    @PostMapping("/{id}/test")
    public ApiResponse<ServerView> test(@PathVariable String id) {
        McpClientManager clients = clientManager();
        int count = clients.test(id); refreshTools();
        McpProperties.Server server = clients.servers().stream().filter(item -> item.getName().equals(id)).findFirst().orElseThrow();
        ServerView value = view(server); return ApiResponse.ok(new ServerView(value.id(), value.name(), value.transport(), value.enabled(), value.url(), value.command(), value.args(), value.envConfigured(), "CONNECTED", count, null));
    }

    private McpClientManager clientManager() {
        return clients.getObject();
    }
    private void refreshTools() {
        provider.getObject().refresh();
        tools.getObject().refresh();
    }
    private ServerView view(McpProperties.Server server) {
        return new ServerView(server.getName(), server.getName(), server.getType().toUpperCase(), server.isEnabled(),
                server.getUrl(), server.getCommand(), server.getArgs(), !server.getEnv().isEmpty(), "CONFIGURED", null, null);
    }
}
