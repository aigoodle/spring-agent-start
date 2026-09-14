package io.github.aigoodle.plugin.agent;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.connector.*;
import io.github.aigoodle.connector.execution.*;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import io.github.aigoodle.tool.execution.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginAgentRuntimeTest {
    @Test void explicitlyOptedInActionRunsThroughGatewayWithCallerIdentity() {
        var registry = mock(ConnectorRegistry.class);
        var key = new ConnectorKey("plugin", "video");
        when(registry.get(key)).thenReturn(new ConnectorDefinition(key, "Video", null, "1", ConnectorSource.NATIVE,
                null, null, "{}", List.of(new ConnectorActionDefinition("plan", null, null, null, null, false, null, null,
                Map.of("agentRuntime", true))), ConnectorTrustLevel.REVIEWED, null, Map.of()));
        ConnectorExecutionGateway gateway = request -> {
            assertThat(request.context().tenantId()).isEqualTo("tenant-a");
            assertThat(request.context().userId()).isEqualTo("user-a");
            assertThat(request.arguments()).containsEntry("query", "Write");
            return ConnectorResult.success(Map.of("text", "Script"));
        };
        var runtime = new PluginAgentRuntime(() -> gateway, () -> registry,
                () -> new ToolExecutionContext("e", "tenant-a", "user-a", null, Map.of()));
        var definition = AgentDefinition.builder().tenantId("tenant-a").runtimeType("PLUGIN").runtimeRef("video/plan").build();
        assertThat(runtime.run(definition, AgentRequest.of("Write")).getText()).isEqualTo("Script");
        definition.setTenantId("tenant-b");
        assertThatThrownBy(() -> runtime.run(definition, AgentRequest.of("Write"))).hasMessageContaining("tenants differ");
    }
}
