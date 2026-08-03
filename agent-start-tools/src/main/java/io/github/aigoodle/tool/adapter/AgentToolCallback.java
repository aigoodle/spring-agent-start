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

    private final AgentTool agentTool;

    public AgentToolCallback(AgentTool agentTool) {
        this.agentTool = agentTool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(agentTool.name())
                .description(agentTool.description())
                .inputSchema(agentTool.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> parsedArguments = JsonUtils.parseMap(toolInput);
        Map<String, Object> arguments = parsedArguments == null ? Map.of() : parsedArguments;
        Object toolResult = agentTool.execute(arguments);
        if (toolResult == null) {
            return "";
        }
        return toolResult instanceof String text ? text : JsonUtils.toJson(toolResult);
    }
}
