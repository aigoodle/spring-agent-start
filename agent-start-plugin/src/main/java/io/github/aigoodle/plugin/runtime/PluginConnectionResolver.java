package io.github.aigoodle.plugin.runtime;

import io.github.aigoodle.connector.execution.ConnectorExecutionRequest;
import io.github.aigoodle.connector.connection.ConnectorConnectionService.ResolvedConnection;

@FunctionalInterface
public interface PluginConnectionResolver {
    ResolvedConnection resolve(ConnectorExecutionRequest request);
}
