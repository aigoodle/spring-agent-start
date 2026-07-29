package io.github.aigoodle.tool.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configures MCP servers whose tools should be exposed to agents
 * ({@code spring-agent.tools.mcp.servers[*]}).
 */
@ConfigurationProperties(prefix = "spring-agent.tools.mcp")
public class McpProperties {

    private List<Server> servers = new ArrayList<>();

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = servers;
    }

    public static class Server {
        /** Logical name (used for logging / tool grouping). */
        private String name;
        /** {@code stdio} (spawn a process) or {@code http} (connect to an SSE endpoint). */
        private String type = "stdio";
        /** stdio: the executable, e.g. {@code npx} or {@code java}. */
        private String command;
        /** stdio: command arguments. */
        private List<String> args = new ArrayList<>();
        /** http: the MCP server base URL. */
        private String url;
        private int requestTimeoutSeconds = 30;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }
    }
}
