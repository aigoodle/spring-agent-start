package io.github.aigoodle.knowledge.chunk;

import io.github.aigoodle.knowledge.chunk.template.QaChunker;
import io.github.aigoodle.knowledge.config.ProcessRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QaChunkerTest {

    private final QaChunker chunker = new QaChunker();

    @Test
    void recognizesChineseFullWidthQuestionAndAnswerMarkers() {
        List<Chunk> chunks = chunker.chunk(
                "问题：什么是检索增强生成？\n答案：先检索知识，再生成答案。",
                new ProcessRule(),
                Map.of("source", "faq"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getMetadata())
                .containsEntry("question", "什么是检索增强生成？")
                .containsEntry("source", "faq");
        assertThat(chunks.getFirst().getContent())
                .isEqualTo("Q: 什么是检索增强生成？\nA: 先检索知识，再生成答案。");
    }

    @Test
    void recognizesNumberedEnglishQuestionMarkers() {
        List<Chunk> chunks = chunker.chunk(
                "Q12: Can questions be numbered?\nA: Yes.",
                new ProcessRule(),
                Map.of());

        assertThat(chunks).singleElement()
                .extracting(Chunk::getContent)
                .isEqualTo("Q: Can questions be numbered?\nA: Yes.");
    }
}
