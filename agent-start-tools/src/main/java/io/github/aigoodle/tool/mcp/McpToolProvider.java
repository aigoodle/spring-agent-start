package io.github.aigoodle.tool.mcp;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.ToolProvider;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ToolProvider} that surfaces the tools of every configured MCP server. The
 * tools join the {@code ToolRegistry} alongside built-in tools, so agents can call them
 * transparently. This is the MCP integration the {@code ToolProvider} SPI was designed
 * for. Tool discovery is cached after the first call; a failing server is skipped (it
 * never breaks startup).
 */
public class McpToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    private final McpClientManager manager;
    private volatile List<AgentTool> cached;

    public McpToolProvider(McpClientManager manager) {
        this.manager = manager;
    }

    @Override
    public List<AgentTool> getTools() {
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            List<AgentTool> tools = new ArrayList<>();
            for (McpProperties.Server server : manager.servers()) {
                try {
                    McpSyncClient client = manager.client(server);
                    for (McpSchema.Tool tool : client.listTools().tools()) {
                        String schema = tool.inputSchema() == null ? null : JsonUtils.toJson(tool.inputSchema());
                        tools.add(new McpAgentTool(client, tool.name(), tool.description(), schema));
                    }
                    log.info("MCP server '{}' contributed {} tool(s)", server.getName(), client.listTools().tools().size());
                } catch (Exception e) {
                    log.error("Failed to load tools from MCP server '{}': {}", server.getName(), e.getMessage());
                }
            }
            cached = tools;
            return tools;
        }
    }
}
