package io.github.aigoodle.knowledge.chunk;

import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.enums.ChunkingTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {

    private final ChunkerRegistry registry = new ChunkerRegistry(List.of());

    @Test
    void naiveSplitsLongTextIntoBoundedChunks() {
        ProcessRule rule = new ProcessRule();
        rule.setChunkTokens(20);
        rule.setOverlapTokens(5);
        String text = ("This is sentence number one. This is sentence number two. "
                + "Here comes a third sentence. And a fourth one follows. "
                + "Fifth sentence appears here. Sixth and final sentence ends it.").repeat(3);

        List<Chunk> chunks = registry.get(ChunkingTemplate.NAIVE).chunk(text, rule, Map.of());
        assertTrue(chunks.size() > 1, "long text should produce multiple chunks");
        for (Chunk c : chunks) {
            assertTrue(c.tokenCount() <= 20 * 2, "chunk should respect (approx) token budget: " + c.tokenCount());
            assertFalse(c.getContent().isBlank());
        }
        // positions are sequential
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getPosition());
        }
    }

    @Test
    void oneChunkerReturnsSingleChunk() {
        List<Chunk> chunks = registry.get(ChunkingTemplate.ONE)
                .chunk("short doc", new ProcessRule(), Map.of());
        assertEquals(1, chunks.size());
        assertEquals("short doc", chunks.get(0).getContent());
    }

    @Test
    void markdownChunkerCapturesHeadingPath() {
        String md = "# Title\nIntro text.\n## Section A\nAlpha content here.\n## Section B\nBeta content here.";
        List<Chunk> chunks = registry.get(ChunkingTemplate.MARKDOWN)
                .chunk(md, new ProcessRule(), Map.of());
        assertFalse(chunks.isEmpty());
        boolean hasHeading = chunks.stream().anyMatch(c ->
                c.getMetadata().containsKey("heading")
                        && String.valueOf(c.getMetadata().get("heading")).contains("Section"));
        assertTrue(hasHeading, "markdown chunks should carry heading metadata");
    }

    @Test
    void qaChunkerDetectsPairs() {
        String text = "Q: What is RAG?\nA: Retrieval augmented generation.\n"
                + "Q: What is an embedding?\nA: A dense vector representation of text.";
        List<Chunk> chunks = registry.get(ChunkingTemplate.QA).chunk(text, new ProcessRule(), Map.of());
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getMetadata().containsKey("question"));
        assertTrue(chunks.get(0).getContent().contains("Retrieval augmented generation"));
    }

    @Test
    void parentChildLinksChildrenToParent() {
        ProcessRule rule = new ProcessRule();
        rule.setParentChunkTokens(60);
        rule.setChunkTokens(15);
        String text = ("Section one talks about cats and their behaviour in detail. "
                + "Section two talks about dogs and their loyalty over the years. "
                + "Section three talks about birds and their migration patterns.").repeat(2);
        List<Chunk> chunks = registry.get(ChunkingTemplate.PARENT_CHILD).chunk(text, rule, Map.of());
        assertFalse(chunks.isEmpty());
        for (Chunk c : chunks) {
            assertNotNull(c.getParentContent(), "each child must reference its parent");
            assertTrue(c.getParentContent().length() >= c.getContent().length());
        }
    }
}
