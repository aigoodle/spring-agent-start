package io.github.aigoodle.tool.mcp;

import io.github.aigoodle.tool.AgentTool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolProviderTest {

    @Test
    void discoversEachServerOnceAndPublishesAnImmutableCache() {
        McpProperties.Server server = new McpProperties.Server();
        server.setName("inventory");
        McpSchema.Tool remoteTool = mock(McpSchema.Tool.class);
        when(remoteTool.name()).thenReturn("find-product");
        when(remoteTool.description()).thenReturn("Find a product by SKU");

        McpSchema.ListToolsResult discoveryResult = mock(McpSchema.ListToolsResult.class);
        when(discoveryResult.tools()).thenReturn(List.of(remoteTool));
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(discoveryResult);
        McpClientManager clientManager = mock(McpClientManager.class);
        when(clientManager.servers()).thenReturn(List.of(server));
        when(clientManager.client(server)).thenReturn(client);
        McpToolProvider provider = new McpToolProvider(clientManager);

        List<AgentTool> firstDiscovery = provider.getTools();
        List<AgentTool> cachedDiscovery = provider.getTools();

        assertThat(firstDiscovery).isSameAs(cachedDiscovery).hasSize(1);
        assertThat(firstDiscovery.getFirst().name()).isEqualTo("find-product");
        assertThatThrownBy(() -> firstDiscovery.clear())
                .isInstanceOf(UnsupportedOperationException.class);
        verify(client).listTools();
        verify(clientManager).client(server);
    }
}
