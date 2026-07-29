package io.github.aigoodle.tool.mcp;

import io.github.aigoodle.tool.AgentTool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapts a single tool exposed by an MCP server to the {@link AgentTool} SPI, so it can
 * be used by agents and workflows exactly like a built-in tool. Calls are proxied to the
 * MCP server over the manager's client.
 */
public class McpAgentTool implements AgentTool {

    private final McpSyncClient client;
    private final String name;
    private final String description;
    private final String inputSchema;

    public McpAgentTool(McpSyncClient client, String name, String description, String inputSchema) {
        this.client = client;
        this.name = name;
        this.description = description == null ? name : description;
        this.inputSchema = inputSchema;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String inputSchema() {
        return inputSchema == null ? AgentTool.super.inputSchema() : inputSchema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(name, args));
        String text = result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
        if (Boolean.TRUE.equals(result.isError())) {
            return "error: " + text;
        }
        return text;
    }
}
