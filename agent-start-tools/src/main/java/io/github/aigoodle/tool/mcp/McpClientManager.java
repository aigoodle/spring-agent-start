package io.github.aigoodle.tool.mcp;

import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.common.crypto.AesGcmTextEncryptor;
import io.github.aigoodle.common.crypto.TextEncryptor;
import io.github.aigoodle.common.util.JsonUtils;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches an {@link McpSyncClient} per configured MCP server (stdio or HTTP),
 * lazily connecting + initialising on first use. Closed gracefully on shutdown.
 */
public class McpClientManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final List<McpProperties.Server> servers;
    private final Path configFile;
    private final TextEncryptor encryptor;
    private final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();
    private final ConcurrentHashMap<String, McpSyncClient> clientsByServerName =
            new ConcurrentHashMap<>();

    public McpClientManager(List<McpProperties.Server> servers) {
        this(servers, null, "mcp-test-secret");
    }

    public McpClientManager(List<McpProperties.Server> servers, String configFile, String encryptionSecret) {
        this.servers = new ArrayList<>(servers == null ? List.of() : servers);
        this.configFile = configFile == null || configFile.isBlank() ? null : Path.of(configFile).toAbsolutePath().normalize();
        this.encryptor = new AesGcmTextEncryptor(encryptionSecret);
        loadManagedServers();
    }

    public synchronized List<McpProperties.Server> servers() {
        return List.copyOf(servers);
    }

    public synchronized void upsert(McpProperties.Server server) {
        if (server == null || server.getName() == null || server.getName().isBlank())
            throw new IllegalArgumentException("MCP server name is required");
        remove(server.getName());
        servers.add(server);
        persistManagedServers();
    }

    public synchronized boolean remove(String name) {
        McpSyncClient client = clientsByServerName.remove(name);
        if (client != null) closeClient(name, client);
        boolean removed = servers.removeIf(server -> server.getName().equals(name));
        if (removed) persistManagedServers();
        return removed;
    }

    public synchronized int test(String name) {
        McpProperties.Server server = servers.stream().filter(item -> item.getName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + name));
        McpSyncClient old = clientsByServerName.remove(name);
        if (old != null) closeClient(name, old);
        return client(server).listTools().tools().size();
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
                throw new PlatformException("mcp_url_required",
                        "MCP server '" + server.getName() + "' is type=http but has no url", null);
            }
            return HttpClientSseClientTransport.builder(server.getUrl()).build();
        }
        if (server.getCommand() == null || server.getCommand().isBlank()) {
            throw new PlatformException("mcp_command_required",
                    "MCP server '" + server.getName() + "' is type=stdio but has no command", null);
        }
        ServerParameters serverParameters = ServerParameters.builder(server.getCommand())
                .args(server.getArgs())
                .env(server.getEnv())
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

    private record StoredServer(String name, String type, String command, List<String> args,
                                String url, int requestTimeoutSeconds, boolean enabled,
                                String encryptedEnv) {}

    private void loadManagedServers() {
        if (configFile == null || !Files.isRegularFile(configFile)) return;
        try {
            List<StoredServer> stored = JsonUtils.parseList(Files.readString(configFile), StoredServer.class);
            for (StoredServer value : stored) {
                McpProperties.Server server = new McpProperties.Server();
                server.setName(value.name()); server.setType(value.type()); server.setCommand(value.command());
                server.setArgs(value.args()); server.setUrl(value.url()); server.setEnabled(value.enabled());
                server.setRequestTimeoutSeconds(value.requestTimeoutSeconds());
                String json = encryptor.decrypt(value.encryptedEnv());
                @SuppressWarnings("unchecked") Map<String, String> env = json == null ? Map.of()
                        : JsonUtils.mapper().convertValue(JsonUtils.parseMap(json), Map.class);
                server.setEnv(env);
                servers.removeIf(item -> item.getName().equals(server.getName()));
                servers.add(server);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to load MCP admin configuration from " + configFile, failure);
        }
    }

    private void persistManagedServers() {
        if (configFile == null) return;
        try {
            Path parent = configFile.getParent(); if (parent != null) Files.createDirectories(parent);
            List<StoredServer> stored = servers.stream().map(server -> new StoredServer(server.getName(), server.getType(),
                    server.getCommand(), server.getArgs(), server.getUrl(), server.getRequestTimeoutSeconds(),
                    server.isEnabled(), encryptor.encrypt(JsonUtils.toJson(server.getEnv())))).toList();
            Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(temporary, JsonUtils.toJson(stored), StandardCharsets.UTF_8);
            try { Files.move(temporary, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to persist MCP admin configuration to " + configFile, failure);
        }
    }
}
