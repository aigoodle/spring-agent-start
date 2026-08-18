package io.github.aigoodle.connector.openclaw;

import io.github.aigoodle.connector.ConnectorDefinition;
import io.github.aigoodle.connector.ConnectorKey;
import io.github.aigoodle.connector.execution.ConnectorExecutionContext;
import io.github.aigoodle.connector.execution.ConnectorExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenClawConnectorProviderTest {
    @Test
    void mapsPluginsAndInvokesGatewayTools() {
        FakeClient client = new FakeClient();
        OpenClawConnectorProvider provider = new OpenClawConnectorProvider(client);

        List<ConnectorDefinition> definitions = provider.discover();

        assertThat(definitions).singleElement().satisfies(definition -> {
            assertThat(definition.key()).isEqualTo(new ConnectorKey("openclaw", "qq-bot"));
            assertThat(definition.actions()).singleElement().extracting("id").isEqualTo("qq_send");
        });
        var result = provider.execute(new ConnectorExecutionRequest(
                new ConnectorKey("openclaw", "qq-bot"), "qq_send", null, null,
                Map.of("text", "hello"), ConnectorExecutionContext.anonymous()));
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("messageId", "42"));
        assertThat(client.lastArguments).containsEntry("text", "hello");
    }

    @Test
    void gatewayOutageKeepsLastSuccessfulCatalog() {
        FakeClient client = new FakeClient();
        OpenClawConnectorProvider provider = new OpenClawConnectorProvider(client);
        assertThat(provider.discover()).hasSize(1);
        client.unavailable = true;
        assertThat(provider.discover()).hasSize(1);
    }

    private static final class FakeClient implements OpenClawGatewayClient {
        private boolean unavailable;
        private Map<String, Object> lastArguments;
        @Override public OpenClawDtos.RuntimeInfo runtime() { return null; }
        @Override public List<OpenClawDtos.PluginInfo> plugins() {
            if (unavailable) throw new IllegalStateException("offline");
            return List.of(new OpenClawDtos.PluginInfo("qq-bot", "QQ Bot", "1.0", "QQ messaging",
                    true, "MIT", null, Map.of()));
        }
        @Override public List<OpenClawDtos.ToolInfo> tools() {
            return List.of(new OpenClawDtos.ToolInfo("qq-bot", "qq_send", "Send QQ", "Send message",
                    "{\"type\":\"object\"}", "write", List.of(), Map.of()));
        }
        @Override public OpenClawDtos.InvokeResponse invoke(String toolName, OpenClawDtos.InvokeRequest request) {
            lastArguments = request.arguments();
            return new OpenClawDtos.InvokeResponse(true, toolName, Map.of("messageId", "42"),
                    List.of(), null, Map.of());
        }
        @Override public OpenClawDtos.PluginInfo install(OpenClawDtos.InstallRequest request) { return null; }
        @Override public OpenClawDtos.PluginInfo configure(String pluginId, Map<String, Object> config) { return null; }
        @Override public void enable(String pluginId) {}
        @Override public void disable(String pluginId) {}
        @Override public void uninstall(String pluginId) {}
    }
}
