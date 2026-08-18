package io.github.aigoodle.web.controller;

import io.github.aigoodle.tool.ToolDefinition;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.connector.ConnectorDefinition;
import io.github.aigoodle.connector.ConnectorKey;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists tools available to agents and lets the frontend probe one to check what it does.
 */
@RestController
@ConditionalOnBean(ToolRegistry.class)
@RequestMapping("/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ConnectorRegistry connectorRegistry;

    public ToolController(ToolRegistry toolRegistry, ObjectProvider<ConnectorRegistry> connectorRegistry) {
        this.toolRegistry = toolRegistry;
        this.connectorRegistry = connectorRegistry.getIfAvailable();
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(toolRegistry.all().stream().map(this::asView).toList());
    }

    @GetMapping("/{name}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String name) {
        return ApiResponse.ok(asView(toolRegistry.get(name)));
    }

    /**
     * Test-drive a tool with the given args. Bypasses the agent — useful for the
     * "try it" button on the tool details page.
     */
    @PostMapping("/{name}/invoke")
    public ApiResponse<Object> invoke(@PathVariable String name, @RequestBody(required = false) Map<String, Object> args) {
        return ApiResponse.ok(toolRegistry.execute(name, args));
    }

    private Map<String, Object> asView(ToolDefinition tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("inputSchema", tool.inputSchema());
        enrichConnector(view, tool.name());
        return view;
    }

    private void enrichConnector(Map<String, Object> view, String toolName) {
        if (connectorRegistry == null || !toolName.startsWith("connector__")) return;
        String[] parts = toolName.split("__", 4);
        if (parts.length != 4) return;
        try {
            ConnectorDefinition connector = connectorRegistry.get(new ConnectorKey(parts[1], parts[2]));
            var action = connector.action(parts[3]);
            view.put("label", connector.name() + " / " + action.name());
            view.put("category", "Connector");
            view.put("icon", connector.icon());
            view.put("provider", connector.key().provider());
            view.put("connectorId", connector.key().connectorId());
            view.put("actionId", action.id());
            view.put("riskLevel", action.riskLevel().name());
        } catch (RuntimeException ignored) {
            view.put("category", "Connector");
        }
    }
}
