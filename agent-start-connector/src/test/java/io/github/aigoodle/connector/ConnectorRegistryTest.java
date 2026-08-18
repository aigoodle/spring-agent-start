package io.github.aigoodle.connector;

import io.github.aigoodle.connector.execution.ConnectorExecutionRequest;
import io.github.aigoodle.connector.execution.ConnectorResult;
import io.github.aigoodle.connector.provider.ConnectorProvider;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorRegistryTest {
    @Test
    void discoversProviderNeutralDefinitions() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(provider("openclaw", "qq")));
        assertThat(registry.get(new ConnectorKey("openclaw", "qq")).action("send").name())
                .isEqualTo("send");
        assertThat(registry.snapshot().providers()).containsKey("openclaw");
    }

    @Test
    void rejectsDuplicateProviderTypes() {
        assertThatThrownBy(() -> new ConnectorRegistry(List.of(
                provider("openclaw", "qq"), provider("openclaw", "telegram"))))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("Duplicate connector provider");
    }

    private static ConnectorProvider provider(String type, String id) {
        return new ConnectorProvider() {
            public String type() { return type; }
            public List<ConnectorDefinition> discover() {
                return List.of(new ConnectorDefinition(new ConnectorKey(type, id), id, null, "1.0",
                        ConnectorSource.OPENCLAW, null, "messaging", null,
                        List.of(new ConnectorActionDefinition("send", null, null, null, null,
                                false, null, null, Map.of())), ConnectorTrustLevel.REVIEWED,
                        "MIT", Map.of()));
            }
            public ConnectorResult execute(ConnectorExecutionRequest request) {
                return ConnectorResult.success(request.arguments());
            }
        };
    }
}
