package io.github.aigoodle.web.controller;

import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.web.common.ApiResponse;
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
@RequestMapping("${spring-agent.web.base-path:}/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(toolRegistry.all().stream().map(ToolController::asView).toList());
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

    private static Map<String, Object> asView(AgentTool tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("inputSchema", tool.inputSchema());
        return view;
    }
}
