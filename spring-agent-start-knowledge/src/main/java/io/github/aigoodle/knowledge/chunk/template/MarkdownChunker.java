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
 * Splits on Markdown headings, keeping the heading path (e.g. "H1 > H2") as
 * metadata and prepending it to each chunk for better retrieval context. Oversized
 * sections are further split with the recursive splitter.
 */
public class MarkdownChunker implements Chunker {

    @Override
    public ChunkingTemplate template() {
        return ChunkingTemplate.MARKDOWN;
    }

    @Override
    public List<Chunk> chunk(String text, ProcessRule rule, Map<String, Object> baseMetadata) {
        List<Chunk> chunks = new ArrayList<>();
        String[] lines = text.split("\n");
        String[] headingPath = new String[7]; // index = heading level 1..6
        StringBuilder body = new StringBuilder();
        int[] pos = {0};

        for (String line : lines) {
            int level = headingLevel(line);
            if (level > 0) {
                flush(body, headingPath, rule, baseMetadata, chunks, pos);
                for (int i = level + 1; i < headingPath.length; i++) {
                    headingPath[i] = null;
                }
                headingPath[level] = line.replaceFirst("^#{1,6}\\s*", "").strip();
            } else {
                body.append(line).append("\n");
            }
        }
        flush(body, headingPath, rule, baseMetadata, chunks, pos);
        return chunks;
    }

    private void flush(StringBuilder body, String[] headingPath, ProcessRule rule,
                       Map<String, Object> baseMetadata, List<Chunk> chunks, int[] pos) {
        String content = body.toString().strip();
        body.setLength(0);
        if (content.isBlank()) {
            return;
        }
        String path = headingPath(headingPath);
        String prefix = path.isEmpty() ? "" : path + "\n";
        for (String piece : RecursiveSplitter.split(content, rule.getSeparators(),
                rule.getChunkTokens(), rule.getOverlapTokens())) {
            Map<String, Object> md = new HashMap<>(baseMetadata);
            if (!path.isEmpty()) {
                md.put("heading", path);
            }
            chunks.add(new Chunk(prefix + piece, pos[0]++, md));
        }
    }

    private static int headingLevel(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        return (i >= 1 && i <= 6 && i < line.length() && line.charAt(i) == ' ') ? i : 0;
    }

    private static String headingPath(String[] headingPath) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < headingPath.length; i++) {
            if (headingPath[i] != null && !headingPath[i].isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" > ");
                }
                sb.append(headingPath[i]);
            }
        }
        return sb.toString();
    }
}
