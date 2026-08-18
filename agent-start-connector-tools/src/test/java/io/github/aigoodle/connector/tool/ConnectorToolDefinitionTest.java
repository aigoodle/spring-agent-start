package io.github.aigoodle.connector.tool;

import io.github.aigoodle.connector.*;
import io.github.aigoodle.connector.execution.*;
import io.github.aigoodle.tool.execution.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorToolDefinitionTest {
    @Test
    void propagatesCallerContextAndAllowsMissingConversationId() {
        AtomicReference<ConnectorExecutionRequest> captured = new AtomicReference<>();
        ConnectorExecutionGateway gateway = request -> {
            captured.set(request);
            return ConnectorResult.success(Map.of("ok", true));
        };
        ConnectorActionDefinition action = new ConnectorActionDefinition("send", "Send", "Send",
                null, null, false, null, ConnectorRiskLevel.WRITE, Map.of());
        ConnectorDefinition connector = new ConnectorDefinition(new ConnectorKey("openclaw", "qq"),
                "QQ", "QQ", "1", ConnectorSource.OPENCLAW, null, "chat", null,
                List.of(action), ConnectorTrustLevel.REVIEWED, "MIT", Map.of());
        ConnectorToolDefinition tool = new ConnectorToolDefinition(connector, action, gateway);

        Object result = tool.execute(Map.of("text", "hello"),
                new ToolExecutionContext("exec-1", "tenant-1", "user-1", null, Map.of()));

        assertThat(result).isEqualTo(Map.of("ok", true));
        assertThat(captured.get().context().tenantId()).isEqualTo("tenant-1");
        assertThat(captured.get().context().attributes()).containsEntry("tool", tool.name());
    }
}
