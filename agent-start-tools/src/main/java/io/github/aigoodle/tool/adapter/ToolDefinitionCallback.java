package io.github.aigoodle.tool.adapter;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.tool.ToolDefinition;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.Map;

/**
 * Adapts an {@link ToolDefinition} to a Spring AI {@link ToolCallback}, so any tool can be
 * handed to a {@code ChatClient}/agent for function calling. The model's JSON tool
 * input is parsed into a map and the tool's result is serialised back to a string.
 */
public class ToolDefinitionCallback implements ToolCallback {

    private final ToolDefinition tool;

    public ToolDefinitionCallback(ToolDefinition tool) {
        this.tool = tool;
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> parsedArguments = JsonUtils.parseMap(toolInput);
        Map<String, Object> arguments = parsedArguments == null ? Map.of() : parsedArguments;
        Object toolResult = tool.execute(arguments);
        if (toolResult == null) {
            return "";
        }
        return toolResult instanceof String text ? text : JsonUtils.toJson(toolResult);
    }
}
