package io.github.aigoodle.tool.adapter;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.tool.AgentTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Adapts an {@link AgentTool} to a Spring AI {@link ToolCallback}, so any tool can be
 * handed to a {@code ChatClient}/agent for function calling. The model's JSON tool
 * input is parsed into a map and the tool's result is serialised back to a string.
 */
public class AgentToolCallback implements ToolCallback {

    private final AgentTool tool;

    public AgentToolCallback(AgentTool tool) {
        this.tool = tool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> args = JsonUtils.parseMap(toolInput);
        Object result = tool.execute(args == null ? Map.of() : args);
        if (result == null) {
            return "";
        }
        return result instanceof String s ? s : JsonUtils.toJson(result);
    }
}
