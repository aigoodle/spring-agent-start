package io.github.aigoodle.plugin.host;

import io.github.aigoodle.connector.execution.ConnectorExecutionContext;
import java.util.Map;

/** Host-owned adapter. Enforce resource authorization with the supplied identity. */
public interface PluginHostCapability {
    String name();
    Object execute(ConnectorExecutionContext identity, Map<String, Object> arguments);
}
