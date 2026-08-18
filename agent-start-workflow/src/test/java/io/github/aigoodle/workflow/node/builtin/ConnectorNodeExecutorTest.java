package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.connector.execution.ConnectorResult;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectorNodeExecutorTest {
    @Test
    void dispatchesProviderNeutralConnectorWithResolvedVariables() {
        ConnectorNodeExecutor executor = new ConnectorNodeExecutor(request -> {
            assertThat(request.connector().externalForm()).isEqualTo("openclaw:qq-bot");
            assertThat(request.arguments()).containsEntry("message", "hello Ada");
            return ConnectorResult.success(Map.of("messageId", "m-1"));
        });
        ExecutionContext context = ExecutionContext.start(Map.of("name", "Ada"), "c-1", null);
        context.setTenantId("acme");
        NodeDef node = NodeDef.of("send", NodeType.CONNECTOR)
                .with("provider", "openclaw").with("connectorId", "qq-bot")
                .with("actionId", "send_message")
                .with("inputs", Map.of("message", "hello {{#sys.name#}}"));

        assertThat(executor.execute(node, context).getOutputs())
                .containsEntry("result", Map.of("messageId", "m-1"));
    }
}
