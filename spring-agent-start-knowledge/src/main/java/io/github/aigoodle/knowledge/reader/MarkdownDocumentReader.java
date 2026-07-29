package io.github.aigoodle.knowledge.reader;

import java.nio.charset.StandardCharsets;

/**
 * Passes Markdown through verbatim so the {@code MarkdownChunker} can use its
 * headings. Note: Spring AI's markdown reader eagerly parses to Document, dropping
 * headings — we keep the raw text instead.
 */
public class MarkdownDocumentReader implements DocumentReader {

    public static final String NAME = "markdown";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown");
    }

    @Override
    public String read(byte[] bytes, String filename) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
