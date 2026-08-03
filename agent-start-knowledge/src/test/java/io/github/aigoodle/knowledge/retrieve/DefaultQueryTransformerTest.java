package io.github.aigoodle.knowledge.retrieve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryTransformerTest {

    @Test
    void normalizesChineseQuestionAndPunctuation() {
        var variants = new DefaultQueryTransformer().transform("  请问  RAGFlow，如何解析 PDF？ ");
        assertEquals("请问 RAGFlow,如何解析 PDF?", variants.get(0));
        assertTrue(variants.stream().anyMatch(value -> value.contains("RAGFlow")));
        assertTrue(variants.stream().anyMatch(value -> !value.contains("？")));
    }
}
