package io.github.aigoodle.knowledge.reader;

import java.util.List;

/**
 * Facade over {@link DocumentReaderRegistry}. Callers still get simple
 * {@code extractText / extractMarkdown / extractFile} methods but the actual work is
 * delegated to pluggable {@link DocumentReader} beans, so third parties can add
 * proprietary formats without touching this class.
 */
public class DocumentExtractor {

    private final DocumentReaderRegistry registry;

    public DocumentExtractor() {
        this(new DocumentReaderRegistry(List.of(
                new TextDocumentReader(),
                new MarkdownDocumentReader(),
                new HtmlDocumentReader(),
                new TikaDocumentReader()
        )));
    }

    public DocumentExtractor(DocumentReaderRegistry registry) {
        this.registry = registry;
    }

    public String extractText(String text) {
        return text == null ? "" : text;
    }

    /** Markdown is kept verbatim so the {@code MarkdownChunker} can use its headings. */
    public String extractMarkdown(String markdown, String name) {
        return markdown == null ? "" : markdown;
    }

    /**
     * Extract text from an arbitrary payload. The reader is chosen by
     * {@link DocumentReaderRegistry#pick(String)} — extension first, Tika fallback.
     */
    public String extractFile(byte[] bytes, String filename) {
        DocumentReader reader = registry.pick(filename);
        if (reader == null) {
            return bytes == null ? "" : new String(bytes);
        }
        return reader.read(bytes, filename);
    }

    /** Extract using an explicitly named reader — useful for URL/HTML sources etc. */
    public String extract(String readerName, byte[] bytes, String filename) {
        DocumentReader reader = registry.byName(readerName);
        if (reader == null) {
            return extractFile(bytes, filename);
        }
        return reader.read(bytes, filename);
    }

    public DocumentReaderRegistry registry() {
        return registry;
    }
}
