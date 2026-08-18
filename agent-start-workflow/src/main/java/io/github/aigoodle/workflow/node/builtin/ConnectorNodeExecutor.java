package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.connector.ConnectorKey;
import io.github.aigoodle.connector.execution.ConnectorExecutionContext;
import io.github.aigoodle.connector.execution.ConnectorExecutionGateway;
import io.github.aigoodle.connector.execution.ConnectorExecutionRequest;
import io.github.aigoodle.connector.execution.ConnectorResult;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider-neutral connector node; OpenClaw is one provider, not a workflow type. */
public class ConnectorNodeExecutor implements NodeExecutor {
    private final ConnectorExecutionGateway gateway;

    public ConnectorNodeExecutor(ConnectorExecutionGateway gateway) { this.gateway = gateway; }
    @Override public NodeType type() { return NodeType.CONNECTOR; }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        String provider = required(node, "provider");
        String connectorId = required(node, "connectorId");
        String actionId = required(node, "actionId");
        Map<String, Object> arguments = resolveArguments(node.get("inputs"), context);
        ConnectorExecutionContext executionContext = new ConnectorExecutionContext(
                context.getRunId() + ":" + node.getId(), context.getTenantId(), context.getUserId(),
                null, null, context.getRunId(), node.getId(),
                Map.of("conversationId", context.getConversationId() == null ? "" : context.getConversationId()));
        ConnectorResult result = gateway.execute(new ConnectorExecutionRequest(
                new ConnectorKey(provider, connectorId), actionId,
                node.getString("installationId"), node.getString("connectionId"),
                arguments, executionContext));
        if (!result.success()) {
            String message = result.error() == null ? "Connector execution failed" : result.error().message();
            return NodeResult.failure(message);
        }
        Object output = result.data() != null ? result.data() : result.content();
        return NodeResult.of(node.getString("outputKey", "result"), output);
    }

    private static String required(NodeDef node, String key) {
        String value = node.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(
                "Connector node requires '" + key + "'");
        return value;
    }

    private static Map<String, Object> resolveArguments(Object configured, ExecutionContext context) {
        if (!(configured instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> resolved = new LinkedHashMap<>();
        values.forEach((key, value) -> resolved.put(String.valueOf(key),
                value instanceof String template
                        ? VariableResolver.render(template, context.getPool()) : value));
        return resolved;
    }
}
