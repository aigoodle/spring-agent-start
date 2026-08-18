package io.github.aigoodle.tool;

import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.tool.adapter.ToolDefinitionCallback;
import org.springframework.ai.tool.ToolCallback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Central registry of all {@link ToolDefinition}s — statically declared beans plus those
 * contributed by {@link ToolProvider}s (e.g. MCP). Exposes them by name and as Spring
 * AI {@link ToolCallback}s for agents.
 */
public class ToolRegistry {

    private final List<ToolDefinition> declaredTools;
    private final List<ToolProvider> toolProviders;
    private volatile Map<String, ToolDefinition> toolsByName;

    public ToolRegistry(List<ToolDefinition> declaredTools, List<ToolProvider> toolProviders) {
        this.declaredTools = declaredTools == null ? List.of() : List.copyOf(declaredTools);
        this.toolProviders = toolProviders == null ? List.of() : List.copyOf(toolProviders);
        refresh();
    }

    /** Rebuilds an immutable tool snapshot after a dynamic provider changes. */
    public synchronized void refresh() {
        Map<String, ToolDefinition> registeredTools = new LinkedHashMap<>();
        registerAll(registeredTools, declaredTools);
        registerProvidedTools(registeredTools, toolProviders);
        this.toolsByName = Collections.unmodifiableMap(registeredTools);
    }

    public ToolDefinition get(String toolName) {
        ToolDefinition tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new PlatformException("tool_not_found",
                    "No tool named '" + toolName + "'. Available: " + toolsByName.keySet(), null);
        }
        return tool;
    }

    public boolean has(String toolName) {
        return toolsByName.containsKey(toolName);
    }

    public List<ToolDefinition> all() {
        return List.copyOf(toolsByName.values());
    }

    public List<String> names() {
        return List.copyOf(toolsByName.keySet());
    }

    public Object execute(String toolName, Map<String, Object> arguments) {
        return get(toolName).execute(arguments == null ? Map.of() : arguments);
    }

    /** All tools adapted to Spring AI callbacks, for handing to a ChatClient/agent. */
    public List<ToolCallback> toolCallbacks() {
        return toolsByName.values().stream()
                .map(ToolDefinitionCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
    }

    /** Callbacks for a named subset (unknown names are skipped). */
    public List<ToolCallback> toolCallbacks(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        return toolNames.stream()
                .map(toolsByName::get)
                .filter(Objects::nonNull)
                .map(ToolDefinitionCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
    }

    private static void registerProvidedTools(Map<String, ToolDefinition> registeredTools,
                                              List<ToolProvider> toolProviders) {
        if (toolProviders == null) {
            return;
        }
        for (ToolProvider toolProvider : toolProviders) {
            registerAll(registeredTools, toolProvider.getTools());
        }
    }

    private static void registerAll(Map<String, ToolDefinition> registeredTools, List<ToolDefinition> tools) {
        if (tools == null) {
            return;
        }
        for (ToolDefinition tool : tools) {
            registeredTools.put(tool.name(), tool);
        }
    }
}
