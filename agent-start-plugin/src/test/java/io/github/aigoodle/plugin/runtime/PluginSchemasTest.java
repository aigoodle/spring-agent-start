package io.github.aigoodle.plugin.runtime;

import io.github.aigoodle.connector.ConnectorActionDefinition;
import io.github.aigoodle.plugin.PluginManifest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PluginSchemasTest {
    PluginManifest manifest(String input) {
        return new PluginManifest("example", "1", null, null, null, null,
                "{\"type\":\"object\",\"required\":[\"apiKey\"],\"properties\":{\"apiKey\":{\"type\":\"string\",\"minLength\":10}}}", Map.of(),
                List.of(new ConnectorActionDefinition("generate", null, null, input, "{\"type\":\"object\",\"required\":[\"url\"]}", false, null, null, Map.of())), List.of());
    }
    @Test void validatesNestedTypedInputsAndDeclaredOutputs() {
        var schemas = new PluginSchemas(manifest("{\"type\":\"object\",\"required\":[\"duration\"],\"properties\":{\"duration\":{\"type\":\"integer\",\"enum\":[5,10]}}}"));
        schemas.input("generate", Map.of("duration", 5));
        assertThatThrownBy(() -> schemas.input("generate", Map.of("duration", "5"))).hasMessageContaining("input");
        assertThatThrownBy(() -> schemas.output("generate", Map.of())).hasMessageContaining("output");
        assertThatThrownBy(() -> schemas.configuration(Map.of(), Map.of("apiKey", "secret")))
                .hasMessageNotContaining("secret");
    }
    @Test void rejectsNetworkSchemaReferencesDuringDiscovery() {
        assertThatThrownBy(() -> new PluginSchemas(manifest("{\"$ref\":\"http://internal/private\"}")))
                .hasMessageContaining("same document");
    }
}
