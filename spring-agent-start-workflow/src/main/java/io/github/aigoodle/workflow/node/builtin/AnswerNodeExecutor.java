package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.chat.ChatFluxHandle;
import io.github.aigoodle.workflow.chat.ChatStreamSink;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariablePool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a final answer template against the pool. Semantically identical to
 * {@link TemplateTransformNodeExecutor} for blocking runs, but chat flows use
 * ANSWER to mark the message shown back to the user (mirrors Dify's answer node).
 * <p>
 * Config: {@code answer} / {@code template} (template). Outputs: {@code answer}.
 *
 * <h4>Streaming</h4>
 * When {@link ExecutionContext#getChatSink()} is present and streaming, the
 * template is rendered token-by-token so users see the LLM's response arrive
 * incrementally instead of waiting for the whole answer. The renderer walks
 * each template piece in order and pushes to the sink:
 * <ul>
 *   <li>literal chunks between {@code {{#…#}}} references → pushed as one
 *       synchronous chunk (they aren't tokens; sending byte-by-byte would just
 *       add SSE overhead);</li>
 *   <li>a reference resolving to a {@link ChatFluxHandle} → we subscribe to
 *       its shared Flux and push each token as it arrives, blocking this node
 *       until the stream completes so the accumulated text can go into
 *       {@code answer};</li>
 *   <li>any other value → {@code String.valueOf} it and push whole (falls back
 *       to {@link ChatFluxHandle#getFutureMessage()} for handles when the sink
 *       is not streaming — same as the old blocking path).</li>
 * </ul>
 * The final {@code answer} output is still the fully-assembled string so
 * observers / history writers see the complete response.
 */
public class AnswerNodeExecutor implements NodeExecutor {

    /** Same pattern {@code VariableResolver} uses — kept private so the two stay in lockstep. */
    private static final Pattern REF = Pattern.compile("\\{\\{#\\s*([a-zA-Z0-9_\\-.]+)\\s*#}}");

    @Override
    public NodeType type() {
        return NodeType.ANSWER;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String template = node.getString("answer", node.getString("template", ""));
        ChatStreamSink sink = ctx.getChatSink();
        String rendered = renderAndStream(template, ctx.getPool(),
                sink != null && sink.isStreaming() ? sink : null);
        return NodeResult.of("answer", rendered);
    }

    /**
     * Walk the template once, streaming as we go. Returns the fully accumulated
     * text (needed both for the {@code answer} output and for history writers).
     * When {@code sink} is null we still assemble the text the classic way — a
     * {@link ChatFluxHandle} reference resolves via {@code toString()} which
     * blocks until the upstream LLM finishes.
     */
    private static String renderAndStream(String template, VariablePool pool, ChatStreamSink sink) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        StringBuilder acc = new StringBuilder();
        Matcher m = REF.matcher(template);
        int cursor = 0;
        while (m.find()) {
            // Literal chunk before this reference.
            if (m.start() > cursor) {
                String literal = template.substring(cursor, m.start());
                acc.append(literal);
                push(sink, literal);
            }
            Object value = pool.get(m.group(1));
            if (value instanceof ChatFluxHandle handle) {
                acc.append(consumeHandle(handle, sink));
            } else if (value != null) {
                String piece = String.valueOf(value);
                acc.append(piece);
                push(sink, piece);
            }
            cursor = m.end();
        }
        if (cursor < template.length()) {
            String tail = template.substring(cursor);
            acc.append(tail);
            push(sink, tail);
        }
        return acc.toString();
    }

    /**
     * Streaming path for a {@link ChatFluxHandle}. Uses the same
     * {@code .doOnNext(sink::push).blockLast()} idiom {@code ReActStrategy}
     * uses for its typewriter effect — one subscription, one drive, no
     * cross-thread latches. {@code blockLast()} pins this virtual thread until
     * the flux terminates, which is what we want (we need the accumulated
     * text back). Tokens hit {@code sink::push} on whichever thread the
     * upstream flux emits from, exactly as they arrive.
     */
    private static String consumeHandle(ChatFluxHandle handle, ChatStreamSink sink) {
        if (sink == null) {
            return handle.getFutureMessage();
        }
        handle.stream()
                .doOnNext(sink::push)
                .blockLast();
        return handle.snapshot();
    }

    private static void push(ChatStreamSink sink, String chunk) {
        if (sink != null && chunk != null && !chunk.isEmpty()) {
            sink.push(chunk);
        }
    }
}
