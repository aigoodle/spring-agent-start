package io.github.aigoodle.web.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.web.dto.WorkflowSaveRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class Jackson2TreeBridgeTest {

    private final JsonMapper mapper = mapperWithBridge();

    @Test
    void decodesJackson2TreeFromBoot4RequestBody() {
        WorkflowSaveRequest request = mapper.readValue("""
                {"name":"flow","graph":{"nodes":[{"id":"start"}],"edges":[]}}
                """, WorkflowSaveRequest.class);

        assertThat(request.getGraph().path("nodes").get(0).path("id").asText())
                .isEqualTo("start");
    }

    @Test
    void encodesJackson2TreeAsOriginalJsonShape() {
        JsonNode graph = JsonUtils.readTree("""
                {"nodes":[{"id":"start"}],"edges":[],"viewport":{"zoom":1.25}}
                """);

        tools.jackson.databind.JsonNode encoded = mapper.readTree(mapper.writeValueAsString(graph));

        assertThat(encoded.path("nodes").get(0).path("id").asString()).isEqualTo("start");
        assertThat(encoded.path("viewport").path("zoom").asDouble()).isEqualTo(1.25);
        assertThat(encoded.has("containerNode")).isFalse();
    }

    private static JsonMapper mapperWithBridge() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new GoodleWebAutoConfiguration().springAgentJackson2TreeBridge().customize(builder);
        return builder.build();
    }
}
