package io.github.aigoodle.web.controller;

import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A couple of lightweight endpoints the frontend uses to detect the backend, its
 * version, and which optional modules are on the classpath (so the UI can hide
 * navigation items whose backing module wasn't imported).
 */
@RestController
@RequestMapping("${spring-agent.web.base-path:}")
public class SystemController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("status", "up", "name", "spring-agent-start"));
    }

    @GetMapping("/system/info")
    public ApiResponse<Map<String, Object>> info() {
        // Reflection classpath probe: modules the UI shows nav for only when their
        // service bean actually exists. Update as more modules land.
        Map<String, Object> modules = new java.util.LinkedHashMap<>();
        modules.put("model", present("io.github.aigoodle.model.service.ModelService"));
        modules.put("knowledge", present("io.github.aigoodle.knowledge.service.KnowledgeService"));
        modules.put("workflow", present("io.github.aigoodle.workflow.service.WorkflowService"));
        modules.put("agent", present("io.github.aigoodle.agent.service.AgentService"));
        modules.put("tools", present("io.github.aigoodle.tool.ToolRegistry"));
        modules.put("observability", present("io.github.aigoodle.observability.service.LlmMetricsService"));
        modules.put("trigger", present("io.github.aigoodle.trigger.service.TriggerService"));
        return ApiResponse.ok(Map.of(
                "name", "spring-agent-start",
                "version", "0.1.0",
                "modules", modules
        ));
    }

    private static boolean present(String cls) {
        try {
            Class.forName(cls, false, SystemController.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
