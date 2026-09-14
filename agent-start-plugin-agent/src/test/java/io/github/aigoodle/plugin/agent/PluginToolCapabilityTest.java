package io.github.aigoodle.plugin.agent;

import io.github.aigoodle.connector.execution.ConnectorExecutionContext;
import io.github.aigoodle.tool.ToolDefinition;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.tool.execution.ToolExecutionGateway;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginToolCapabilityTest {
    @Test void mcpOrInternalToolUsesGovernedGatewayWithOriginalTenantAndUser() {
        var registry = mock(ToolRegistry.class);
        var tool = mock(ToolDefinition.class);
        when(registry.get("mcp_products_read")).thenReturn(tool);
        ToolExecutionGateway gateway = (selected, args, identity) -> {
            assertThat(selected).isSameAs(tool);
            assertThat(identity.tenantId()).isEqualTo("tenant-a");
            assertThat(identity.ownerId()).isEqualTo("user-a");
            assertThat(identity.metadata()).containsEntry("parentExecutionId", "exec");
            return "product";
        };
        var capability = new PluginToolCapability("product.read", "mcp_products_read", () -> registry, () -> gateway);
        assertThat(capability.execute(new ConnectorExecutionContext("exec", "tenant-a", "user-a", null, null, null, null, Map.of()), Map.of("id", "123"))).isEqualTo("product");
        verify(tool, never()).execute(anyMap());
    }
}
