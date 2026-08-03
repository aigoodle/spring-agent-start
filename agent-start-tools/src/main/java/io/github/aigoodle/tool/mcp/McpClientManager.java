package io.github.aigoodle.tool.mcp;

import io.github.aigoodle.common.exception.AgentException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches an {@link McpSyncClient} per configured MCP server (stdio or HTTP),
 * lazily connecting + initialising on first use. Closed gracefully on shutdown.
 */
public class McpClientManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final List<McpProperties.Server> servers;
    private final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();
    private final ConcurrentHashMap<String, McpSyncClient> clientsByServerName =
            new ConcurrentHashMap<>();

    public McpClientManager(List<McpProperties.Server> servers) {
        this.servers = servers == null ? List.of() : servers;
    }

    public List<McpProperties.Server> servers() {
        return servers;
    }

    public McpSyncClient client(McpProperties.Server server) {
        return clientsByServerName.computeIfAbsent(
                server.getName(), ignoredName -> connect(server));
    }

    private McpSyncClient connect(McpProperties.Server server) {
        McpClientTransport transport = transport(server);
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("spring-agent-start", "0.1.0"))
                .requestTimeout(Duration.ofSeconds(server.getRequestTimeoutSeconds()))
                .build();
        try {
            client.initialize();
            logger.info("Connected to MCP server '{}' ({})", server.getName(), server.getType());
            return client;
        } catch (RuntimeException connectionFailure) {
            closeClient(server.getName(), client);
            throw connectionFailure;
        }
    }

    private McpClientTransport transport(McpProperties.Server server) {
        if ("http".equalsIgnoreCase(server.getType())) {
            if (server.getUrl() == null || server.getUrl().isBlank()) {
                throw new AgentException("mcp_url_required",
                        "MCP server '" + server.getName() + "' is type=http but has no url", null);
            }
            return HttpClientSseClientTransport.builder(server.getUrl()).build();
        }
        if (server.getCommand() == null || server.getCommand().isBlank()) {
            throw new AgentException("mcp_command_required",
                    "MCP server '" + server.getName() + "' is type=stdio but has no command", null);
        }
        ServerParameters serverParameters = ServerParameters.builder(server.getCommand())
                .args(server.getArgs())
                .build();
        return new StdioClientTransport(serverParameters, jsonMapper);
    }

    public void close() {
        clientsByServerName.entrySet().forEach(clientEntry ->
                closeClient(clientEntry.getKey(), clientEntry.getValue()));
        clientsByServerName.clear();
    }

    private static void closeClient(String serverName, McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (RuntimeException closeFailure) {
            logger.warn("Failed to close MCP client '{}': {}",
                    serverName, closeFailure.getMessage());
        }
    }
}
