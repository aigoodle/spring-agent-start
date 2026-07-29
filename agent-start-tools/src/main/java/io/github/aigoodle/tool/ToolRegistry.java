package io.github.aigoodle.tool;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.tool.adapter.AgentToolCallback;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry of all {@link AgentTool}s — statically declared beans plus those
 * contributed by {@link ToolProvider}s (e.g. MCP). Exposes them by name and as Spring
 * AI {@link ToolCallback}s for agents.
 */
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolBeans, List<ToolProvider> providers) {
        if (toolBeans != null) {
            toolBeans.forEach(t -> tools.put(t.name(), t));
        }
        if (providers != null) {
            for (ToolProvider p : providers) {
                List<AgentTool> provided = p.getTools();
                if (provided != null) {
                    provided.forEach(t -> tools.put(t.name(), t));
                }
            }
        }
    }

    public AgentTool get(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new AgentException("tool_not_found",
                    "No tool named '" + name + "'. Available: " + tools.keySet(), null);
        }
        return tool;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public List<AgentTool> all() {
        return new ArrayList<>(tools.values());
    }

    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    public Object execute(String name, Map<String, Object> args) {
        return get(name).execute(args == null ? Map.of() : args);
    }

    /** All tools adapted to Spring AI callbacks, for handing to a ChatClient/agent. */
    public List<ToolCallback> toolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            callbacks.add(new AgentToolCallback(tool));
        }
        return callbacks;
    }

    /** Callbacks for a named subset (unknown names are skipped). */
    public List<ToolCallback> toolCallbacks(List<String> names) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (String name : names) {
            AgentTool tool = tools.get(name);
            if (tool != null) {
                callbacks.add(new AgentToolCallback(tool));
            }
        }
        return callbacks;
    }
}
