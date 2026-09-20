package io.github.aigoodle.tool.adapter;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.tool.ToolDefinition;
import io.github.aigoodle.tool.ToolMetadata;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

/** Makes a native Spring AI {@link ToolCallback}, including an {@code @Tool} method, visible to ToolRegistry. */
public final class SpringAiCallbackToolDefinition implements ToolDefinition, ToolMetadata {
    private final ToolCallback callback;

    public SpringAiCallbackToolDefinition(ToolCallback callback) {
        this.callback = callback;
    }

    @Override public String name() { return callback.getToolDefinition().name(); }
    @Override public String description() { return callback.getToolDefinition().description(); }
    @Override public String inputSchema() { return callback.getToolDefinition().inputSchema(); }
    @Override
    public Object execute(Map<String, Object> args) {
        String result = callback.call(JsonUtils.toJson(args));
        if (result == null || result.isBlank()) return result;
        try {
            var node = JsonUtils.readTree(result);
            if (node.isTextual()) return node.textValue();
            return JsonUtils.mapper().convertValue(node, Object.class);
        } catch (IllegalArgumentException notJson) {
            return result;
        }
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("source", "annotation", "category", "system", "custom", false);
    }
}
