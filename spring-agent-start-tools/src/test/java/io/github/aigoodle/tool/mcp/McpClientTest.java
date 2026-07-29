package io.github.aigoodle.tool.mcp;

import io.github.aigoodle.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real end-to-end MCP test: launches {@link McpTestServer} as a stdio subprocess (the
 * way real MCP servers run), connects the client, discovers its tool through the
 * {@link McpToolProvider} / {@link ToolRegistry}, and invokes it over the protocol.
 */
class McpClientTest {

    @Test
    void discoversAndInvokesToolsFromAStdioMcpServer() {
        McpProperties.Server server = new McpProperties.Server();
        server.setName("test-server");
        server.setType("stdio");
        server.setCommand(javaExecutable());
        server.setArgs(List.of("-cp", System.getProperty("java.class.path"),
                "io.github.aigoodle.tool.mcp.McpTestServer"));

        McpClientManager manager = new McpClientManager(List.of(server));
        try {
            ToolRegistry registry = new ToolRegistry(List.of(), List.of(new McpToolProvider(manager)));

            assertTrue(registry.has("greet"),
                    "the MCP server's 'greet' tool should be registered; have: " + registry.names());

            Object result = registry.execute("greet", Map.of("name", "Alice"));
            assertTrue(String.valueOf(result).contains("Hello, Alice"),
                    "tool call should round-trip through MCP; was: " + result);
        } finally {
            manager.close();
        }
    }

    private static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}
