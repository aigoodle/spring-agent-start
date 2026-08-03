package io.github.aigoodle.web.support;

import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves configured tool names into the descriptors shown by the agent console. */
public final class AgentToolViewMapper {

    private final AgentService agentService;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;

    public AgentToolViewMapper(AgentService agentService,
                               ObjectProvider<ToolRegistry> toolRegistryProvider) {
        this.agentService = agentService;
        this.toolRegistryProvider = toolRegistryProvider;
    }

    public List<Map<String, Object>> toolsOf(String agentId) {
        agentService.require(agentId);
        AppModelConfig modelConfig = agentService.getModelConfig(agentId);
        List<String> configuredToolNames = configuredToolNames(modelConfig);
        ToolRegistry toolRegistry = toolRegistryProvider.getIfAvailable();
        if (configuredToolNames.isEmpty() || toolRegistry == null) {
            return List.of();
        }

        List<Map<String, Object>> toolViews = new ArrayList<>();
        for (String toolName : configuredToolNames) {
            if (toolRegistry.has(toolName)) {
                toolViews.add(toView(toolRegistry.get(toolName)));
            }
        }
        return toolViews;
    }

    private static List<String> configuredToolNames(AppModelConfig modelConfig) {
        if (modelConfig == null) {
            return List.of();
        }
        List<String> toolNames = JsonUtils.parseList(modelConfig.getToolNamesJson(), String.class);
        return toolNames == null ? List.of() : toolNames;
    }

    private static Map<String, Object> toView(AgentTool tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("inputSchema", tool.inputSchema());
        return view;
    }
}
