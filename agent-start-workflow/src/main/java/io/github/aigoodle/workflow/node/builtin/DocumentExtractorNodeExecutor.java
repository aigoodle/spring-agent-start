package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.knowledge.reader.DocumentExtractor;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Turns an inline / referenced file payload into extracted text via the knowledge
 * module's {@link DocumentExtractor}. Config:
 * <ul>
 *   <li>{@code contentRef} — pool path to raw bytes (byte[] or base64 string)</li>
 *   <li>{@code filenameRef} — pool path to the source filename (drives reader choice)</li>
 *   <li>{@code readerName} — explicit reader name (overrides filename detection)</li>
 * </ul>
 * Only wired when the knowledge module is on the classpath.
 * Output: {@code text}.
 */
public class DocumentExtractorNodeExecutor implements NodeExecutor {

    private final DocumentExtractor extractor;

    public DocumentExtractorNodeExecutor(DocumentExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public NodeType type() {
        return NodeType.DOCUMENT_EXTRACTOR;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String contentRef = node.getString("contentRef", "sys.fileContent");
        String filenameRef = node.getString("filenameRef", "sys.filename");
        String readerName = node.getString("readerName");
        // Allow inline template text as a shortcut for plaintext extraction.
        String inlineTemplate = node.getString("text");

        byte[] bytes;
        String filename = String.valueOf(ctx.getPool().get(filenameRef));
        if (inlineTemplate != null && !inlineTemplate.isBlank()) {
            bytes = VariableResolver.render(inlineTemplate, ctx.getPool()).getBytes(StandardCharsets.UTF_8);
            if (filename == null || "null".equals(filename)) {
                filename = "inline.txt";
            }
        } else {
            Object raw = ctx.getPool().get(contentRef);
            bytes = toBytes(raw);
        }
        if (bytes == null) {
            return NodeResult.failure("Document extractor: no content found at '" + contentRef + "'");
        }

        String text = readerName != null && !readerName.isBlank()
                ? extractor.extract(readerName, bytes, filename)
                : extractor.extractFile(bytes, filename);
        return NodeResult.empty()
                .output("text", text)
                .output("filename", filename)
                .output("length", text == null ? 0 : text.length());
    }

    private static byte[] toBytes(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof byte[] b) {
            return b;
        }
        if (raw instanceof String s) {
            try {
                return Base64.getDecoder().decode(s);
            } catch (IllegalArgumentException ignored) {
                return s.getBytes(StandardCharsets.UTF_8);
            }
        }
        return String.valueOf(raw).getBytes(StandardCharsets.UTF_8);
    }
}
