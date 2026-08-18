package io.github.aigoodle.connector.tool;

import io.github.aigoodle.connector.execution.ConnectorExecutionGateway;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import io.github.aigoodle.tool.ToolDefinition;
import io.github.aigoodle.tool.ToolProvider;
import java.util.List;

/** Dynamically projects the connector catalog into the existing tool ecosystem. */
public final class ConnectorToolProvider implements ToolProvider {
    private final ConnectorRegistry registry;
    private final ConnectorExecutionGateway gateway;

    public ConnectorToolProvider(ConnectorRegistry registry, ConnectorExecutionGateway gateway) {
        this.registry = registry;
        this.gateway = gateway;
    }

    @Override
    public List<ToolDefinition> getTools() {
        return registry.all().stream()
                .flatMap(connector -> connector.actions().stream()
                        .map(action -> (ToolDefinition) new ConnectorToolDefinition(connector, action, gateway)))
                .toList();
    }
}
