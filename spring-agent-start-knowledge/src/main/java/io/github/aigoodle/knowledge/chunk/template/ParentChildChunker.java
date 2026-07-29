package io.github.aigoodle.knowledge.chunk.template;

import io.github.aigoodle.knowledge.chunk.Chunk;
import io.github.aigoodle.knowledge.chunk.Chunker;
import io.github.aigoodle.knowledge.chunk.RecursiveSplitter;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.enums.ChunkingTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent-child / small-to-big chunking: the document is split into large parent
 * blocks, each parent into small child chunks. Children are what gets embedded and
 * matched (precise recall) while the parent text is carried along to give the LLM
 * full context. Mirrors Dify's hierarchical mode.
 */
public class ParentChildChunker implements Chunker {

    @Override
    public ChunkingTemplate template() {
        return ChunkingTemplate.PARENT_CHILD;
    }

    @Override
    public List<Chunk> chunk(String text, ProcessRule rule, Map<String, Object> baseMetadata) {
        List<String> parents = rule.getParentMode() == ProcessRule.ParentMode.FULL_DOC
                ? List.of(text)
                : RecursiveSplitter.split(text, rule.getSeparators(), rule.getParentChunkTokens(), 0);
        List<Chunk> children = new ArrayList<>();
        int pos = 0;
        int parentIndex = 0;
        for (String parent : parents) {
            List<String> kids = RecursiveSplitter.split(parent, rule.getSeparators(),
                    rule.getChunkTokens(), rule.getOverlapTokens());
            for (String kid : kids) {
                if (kid.isBlank()) {
                    continue;
                }
                Map<String, Object> md = new HashMap<>(baseMetadata);
                md.put("parentIndex", parentIndex);
                Chunk child = new Chunk(kid, pos++, md);
                child.setParentContent(parent);
                children.add(child);
            }
            parentIndex++;
        }
        return children;
    }
}
