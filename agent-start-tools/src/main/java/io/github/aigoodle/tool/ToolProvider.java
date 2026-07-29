package io.github.aigoodle.tool;

import java.util.List;

/**
 * Contributes a dynamic set of {@link AgentTool}s — e.g. tools fetched from an MCP
 * server, an OpenAPI spec, or a plugin jar. Published as a Spring bean and merged into
 * the {@link ToolRegistry} alongside statically declared {@code AgentTool} beans.
 */
public interface ToolProvider {

    List<AgentTool> getTools();
}
