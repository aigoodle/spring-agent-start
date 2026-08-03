package io.github.aigoodle.knowledge.reader;

import io.github.aigoodle.common.exception.AgentException;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.stream.Collectors;
import io.github.aigoodle.knowledge.reader.model.ParsedDocument;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Generic fallback backed by Apache Tika (via Spring AI's TikaDocumentReader). Handles
 * PDF, DOCX, PPTX, XLSX, RTF and dozens of other binary formats. Always {@code supports}
 * returns {@code true} so it acts as the fallback in {@link DocumentReaderRegistry}.
 */
public class TikaDocumentReader implements DocumentReader {

    public static final String NAME = "tika";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean supports(String filename) {
        // Fallback reader — always claims support so more specific readers win first.
        return true;
    }

    @Override
    public String read(byte[] bytes, String filename) {
        try {
            Resource resource = new NamedByteArrayResource(bytes, filename);
            org.springframework.ai.reader.tika.TikaDocumentReader reader =
                    new org.springframework.ai.reader.tika.TikaDocumentReader(resource);
            return reader.get().stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            throw new AgentException("extract_failed", "Failed to extract text from " + filename, e);
        }
    }

    @Override
    public ParsedDocument parse(byte[] bytes, String filename) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            ToXMLContentHandler handler = new ToXMLContentHandler();
            parser.parse(new ByteArrayInputStream(bytes == null ? new byte[0] : bytes), handler,
                    metadata, new ParseContext());
            var blocks = XhtmlBlockParser.parse(handler.toString());
            String mediaType = metadata.get(Metadata.CONTENT_TYPE);
            String title = metadata.get(TikaCoreProperties.TITLE);
            return ParsedDocument.builder().filename(filename).parser(NAME).mediaType(mediaType)
                    .title(title).blocks(blocks).metadata(Map.of("blockCount", blocks.size())).build();
        } catch (Exception e) {
            throw new AgentException("extract_failed", "Failed to structurally parse " + filename, e);
        }
    }

    /** ByteArrayResource that reports a filename so Tika can pick a parser. */
    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes == null ? new byte[0] : bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
