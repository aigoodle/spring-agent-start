package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMemoryWindowTest {

    @Test
    void readsCurrentDesignerMemoryShape() {
        NodeDef node = llmNode().with("memory", Map.of(
                "window", Map.of("enabled", true, "size", 12)));

        LlmMemoryWindow memoryWindow = LlmMemoryWindow.from(node);

        assertThat(memoryWindow.enabled()).isTrue();
        assertThat(memoryWindow.size()).isEqualTo(12);
    }

    @Test
    void disabledWindowWinsOverConfiguredSize() {
        NodeDef node = llmNode().with("memory", Map.of(
                "window", Map.of("enabled", "off", "size", 12)));

        assertThat(LlmMemoryWindow.from(node).enabled()).isFalse();
    }

    @Test
    void preservesLegacyFlatMemoryConfiguration() {
        NodeDef node = llmNode()
                .with("memoryEnabled", "enabled")
                .with("memoryWindow", 6);

        assertThat(LlmMemoryWindow.from(node).size()).isEqualTo(6);
    }

    @Test
    void enabledFlagWithoutSizeUsesReadableDefault() {
        NodeDef node = llmNode().with("memoryEnabled", true);

        assertThat(LlmMemoryWindow.from(node).size()).isEqualTo(LlmMemoryWindow.DEFAULT_SIZE);
    }

    private static NodeDef llmNode() {
        return NodeDef.of("llm", NodeType.LLM);
    }
}
