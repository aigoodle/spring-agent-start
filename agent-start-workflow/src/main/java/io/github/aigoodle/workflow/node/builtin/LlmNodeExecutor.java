package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.PromptTemplateService;
import io.github.aigoodle.workflow.chat.ChatFluxHandle;
import io.github.aigoodle.workflow.chat.ChatStreamSink;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.memory.WorkflowConversationMemory;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls an LLM with a rendered system + user prompt. Config:
 * {@code modelProvider} + {@code modelName} (required — either flat on the
 * node or under {@code model}), {@code systemPrompt} (template),
 * {@code userPrompt}/{@code prompt} (template), or
 * {@code systemPromptTemplateId} (references a saved Prompt template, rendered
 * against the variable pool). Output: {@code text}.
 * <p>
 * When both {@code systemPromptTemplateId} and {@code systemPrompt} are set, the
 * template wins — inline prompts are the fallback for authoring.
 *
 * <h4>Conversation memory (Dify parity)</h4>
 * When the designer's LLM node card enables the memory window
 * ({@code memory.window.enabled = true}), the executor pulls the last
 * {@code memory.window.size} turns of the current conversation via the injected
 * {@link WorkflowConversationMemory} bean and inserts them between the system
 * prompt and the freshly-rendered user prompt. This lets multi-turn chat apps
 * built on the workflow engine reason over history without every LLM node
 * having to re-implement the load. When no memory bean is registered (e.g. the
 * agent module is missing from the classpath), memory is silently skipped and
 * the node behaves like a single-shot call — the same failure mode a
 * fresh conversation would show.
 *
 * <h4>Streaming vs blocking output (mirrors old spring-agent-start's
 * {@code LLMNode} + {@code ChatPenetrate})</h4>
 * The node <b>defers the actual LLM call</b> as long as it can, and picks a
 * mode based on how the response will be consumed:
 * <ol>
 *   <li><b>Structured output</b> — {@code structOutputEnabled=true} or a
 *       {@code structOutput} schema present — must block: we call
 *       {@code spec.call()} synchronously and JSON-parse the result so the
 *       parsed map is available as {@code struct} + flat {@code struct.<field>}
 *       keys for downstream binding nodes.</li>
 *   <li><b>Streaming context</b> — {@link ExecutionContext#getChatSink()} is
 *       non-null and {@link ChatStreamSink#isStreaming() streaming} — we skip
 *       {@code ChatClient} altogether and call {@code chatModel.stream(prompt)}
 *       directly (Spring AI 1.1.2's ChatClient stream wraps the response in a
 *       {@code MessageAggregator} for tool-call detection that <b>buffers</b>
 *       upstream tokens before subscribers see them, defeating typewriter
 *       streaming — direct ChatModel access is what {@code MeteringChatModel}
 *       already decorates). The delta text is mapped out of each
 *       {@link ChatResponse} chunk and wrapped in a {@link ChatFluxHandle} as
 *       {@code text}. The LLM API is <i>not</i> hit yet; the first downstream
 *       subscriber (typically the {@code ANSWER} node) triggers the request.
 *       Nodes that need the whole text (template render / IF_ELSE / parameter
 *       extraction) call {@code String.valueOf(handle)} which delegates to
 *       {@link ChatFluxHandle#getFutureMessage()} and blocks — so the same
 *       output works transparently for both stream and non-stream consumers.</li>
 *   <li><b>Blocking context</b> — no sink or {@code isStreaming() == false} —
 *       classic {@code spec.call().content()} returning a plain {@link String},
 *       matching the pre-streaming behaviour so unit tests / batch runs are
 *       unchanged.</li>
 * </ol>
 */
public class LlmNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmNodeExecutor.class);

    /** Cap the injected history so a runaway memory backend can't blow the prompt. */
    private static final int MAX_MEMORY_TURNS = 50;
    /** Fallback window when config says memory is on but omits a size. */
    private static final int DEFAULT_MEMORY_WINDOW = 10;

    private final ModelService modelService;
    private final PromptTemplateService promptTemplateService;
    private final WorkflowConversationMemory conversationMemory;

    public LlmNodeExecutor(ModelService modelService) {
        this(modelService, null, null);
    }

    public LlmNodeExecutor(ModelService modelService, PromptTemplateService promptTemplateService) {
        this(modelService, promptTemplateService, null);
    }

    public LlmNodeExecutor(ModelService modelService,
                            PromptTemplateService promptTemplateService,
                            WorkflowConversationMemory conversationMemory) {
        this.modelService = modelService;
        this.promptTemplateService = promptTemplateService;
        this.conversationMemory = conversationMemory;
    }

    @Override
    public NodeType type() {
        return NodeType.LLM;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String system = resolveSystem(node, ctx);
        String userTemplate = node.getString("userPrompt", node.getString("prompt", ""));
        String user = VariableResolver.render(userTemplate, ctx.getPool());

        List<Message> messages = new ArrayList<>();
        if (!system.isBlank()) {
            messages.add(new SystemMessage(system));
        }
        appendMemory(node, ctx, messages);
        messages.add(new UserMessage(user));

        ChatStreamSink sink = ctx.getChatSink();
        boolean structured = isStructured(node);
        boolean wantStream = !structured && sink != null && sink.isStreaming();

        if (wantStream) {
            // Skip ChatClient entirely for the streaming path. In Spring AI 1.1.2
            // ChatClient.prompt().stream() wraps the response Flux with
            // MessageAggregator + observation callbacks for tool-call detection,
            // and that layer buffers upstream tokens before they reach downstream
            // subscribers. Calling ChatModel.stream(Prompt) directly hits the
            // metered/decorated ChatModel with no aggregation in between, so
            // each SSE chunk from the provider passes straight through our
            // doOnNext → cache → sink::push chain in real time.
            ChatModel chatModel;
            try {
                chatModel = NodeModelResolver.resolveModel(node, ctx, modelService);
            } catch (IllegalArgumentException e) {
                return NodeResult.failure("LLM node requires modelProvider + modelName");
            }
            Prompt prompt = new Prompt(messages, resolveChatOptions(node, chatModel));
            Flux<String> tokens = chatModel.stream(prompt)
                    .mapNotNull(LlmNodeExecutor::extractDelta)
                    .filter(s -> !s.isEmpty());
            return NodeResult.of("text", new ChatFluxHandle(tokens));
        }

        // Blocking + structured paths stay on ChatClient — .call() has no
        // aggregator quirk and its prompt-template advisors are Call-only
        // (which is fine here, we're not going near .stream()).
        ChatClient client;
        try {
            client = NodeModelResolver.resolve(node, ctx, modelService);
        } catch (IllegalArgumentException e) {
            return NodeResult.failure("LLM node requires modelProvider + modelName");
        }
        ChatClient.ChatClientRequestSpec spec = client.prompt().messages(messages);
        ChatOptions perNode = NodeModelResolver.perNodeOptions(node);
        if (perNode != null) {
            spec = spec.options(perNode);
        }
        if (structured) {
            return runStructured(spec);
        }
        String text = spec.call().content();
        return NodeResult.of("text", text);
    }

    /**
     * Options for the streaming path. Falls back to the chat model's own
     * defaults when the node has no per-request overrides — we need a non-null
     * options here so provider defaults (temperature / maxTokens set via
     * credentials) still apply.
     */
    private static ChatOptions resolveChatOptions(NodeDef node, ChatModel chatModel) {
        ChatOptions perNode = NodeModelResolver.perNodeOptions(node);
        return perNode != null ? perNode : chatModel.getDefaultOptions();
    }

    /**
     * Pull the delta text out of one streaming {@link ChatResponse} chunk.
     * Providers emit an empty text on the first / last frame (role-only or
     * usage-only) — those get filtered out downstream so the sink only sees
     * real content.
     */
    private static String extractDelta(ChatResponse chunk) {
        if (chunk == null) return null;
        Generation gen = chunk.getResult();
        if (gen == null) return null;
        AssistantMessage msg = gen.getOutput();
        if (msg == null) return null;
        String text = msg.getText();
        return text == null ? "" : text;
    }

    /**
     * A node is "structured" when the author asked for JSON parsing — either
     * an explicit boolean toggle or, in its absence, by attaching a
     * {@code structOutput} schema. An explicit {@code structOutputEnabled=false}
     * wins over a stray leftover schema so authors can disable without deleting
     * the config.
     */
    private static boolean isStructured(NodeDef node) {
        Object flag = node.get("structOutputEnabled");
        if (flag != null) {
            return flag instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(flag));
        }
        return node.get("structOutput") != null;
    }

    /**
     * Blocking call + best-effort JSON parse. Exposes both the raw {@code text}
     * (so nodes that only want the string still work) and — if the model
     * returned a JSON object — a {@code struct} map plus per-field
     * {@code struct.<key>} entries so authors can bind them like
     * {@code {{#llm.struct.summary#}}}. A malformed response degrades to
     * text-only rather than failing the node — matches the old
     * {@code LLMNode.run()} semantics (try/catch swallow around
     * {@code formatJsonObject}).
     */
    @SuppressWarnings("unchecked")
    private static NodeResult runStructured(ChatClient.ChatClientRequestSpec spec) {
        String content = spec.call().content();
        NodeResult result = NodeResult.of("text", content);
        if (content == null || content.isBlank()) {
            return result;
        }
        try {
            Object parsed = JsonUtils.parse(content, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> struct = (Map<String, Object>) map;
                result.output("struct", struct);
                for (Map.Entry<String, Object> e : struct.entrySet()) {
                    result.output("struct." + e.getKey(), e.getValue());
                }
            }
        } catch (Exception ignore) {
            // Non-JSON response — text output is still usable, no need to fail.
        }
        return result;
    }

    /**
     * A stored prompt template beats an inline {@code systemPrompt}. Rendering uses
     * a flat view of the variable pool — every namespace becomes a top-level key —
     * so templates like {@code {{#input#}}} work whether the caller writes to
     * {@code sys.input} or a node id {@code start.input}.
     */
    private String resolveSystem(NodeDef node, ExecutionContext ctx) {
        String templateId = node.getString("systemPromptTemplateId");
        if (templateId != null && !templateId.isBlank() && promptTemplateService != null) {
            var tpl = promptTemplateService.get(templateId);
            if (tpl != null) {
                return promptTemplateService.render(tpl.getContent(), flatVars(ctx));
            }
        }
        return VariableResolver.render(node.getString("systemPrompt", ""), ctx.getPool());
    }

    /**
     * Prompt templates use bare variable names, not namespaced paths, so flatten the
     * pool into one map. Later namespaces overwrite earlier ones (mimicking Dify's
     * global-variable convention where the current node wins).
     */
    private static Map<String, Object> flatVars(ExecutionContext ctx) {
        Map<String, Object> flat = new HashMap<>();
        var snapshot = ctx.getPool().snapshot();
        // sys first, then node namespaces on top.
        Map<String, Object> sys = snapshot.get("sys");
        if (sys != null) {
            flat.putAll(sys);
        }
        for (var e : snapshot.entrySet()) {
            if (!"sys".equals(e.getKey())) {
                flat.putAll(e.getValue());
            }
        }
        return flat;
    }

    /**
     * When the designer enabled the node's memory window and a
     * {@link WorkflowConversationMemory} bean is available, load up to
     * {@code memory.window.size} recent turns for the current conversation and
     * append them to the outgoing message list. Roles map straight into Spring
     * AI's {@link SystemMessage} / {@link UserMessage} / {@link AssistantMessage}
     * types; unknown roles fall back to {@code UserMessage} so a badly-tagged
     * row still contributes context rather than silently disappearing.
     *
     * <p>Failures in the memory backend are logged and swallowed — the node
     * still runs against the fresh prompt so a broken memory service can't
     * take chats offline.</p>
     */
    private void appendMemory(NodeDef node, ExecutionContext ctx, List<Message> messages) {
        if (conversationMemory == null) return;
        String conversationId = ctx.getConversationId();
        if (conversationId == null || conversationId.isBlank()) return;
        int size = memoryWindowSize(node);
        if (size <= 0) return;
        try {
            List<WorkflowConversationMemory.ConversationTurn> turns =
                    conversationMemory.load(conversationId, Math.min(size, MAX_MEMORY_TURNS));
            if (turns == null || turns.isEmpty()) return;
            for (WorkflowConversationMemory.ConversationTurn turn : turns) {
                Message msg = toMessage(turn);
                if (msg != null) messages.add(msg);
            }
        } catch (Exception ex) {
            log.warn("Memory load skipped for node {} / conversation {}: {}",
                    node.getId(), conversationId, ex.getMessage());
        }
    }

    /**
     * Resolve the effective memory window from the node config. Recognises
     * three shapes (in order): the designer's nested
     * {@code memory.window.{enabled,size}} object, a flat top-level
     * {@code memoryEnabled} + {@code memoryWindow} pair, and — for authors who
     * only set the size — a bare {@code memory.window.size} that implies
     * enabled. Returns 0 when memory should not be loaded so the caller can
     * skip the round-trip entirely.
     */
    @SuppressWarnings("unchecked")
    private static int memoryWindowSize(NodeDef node) {
        Object memoryCfg = node.get("memory");
        if (memoryCfg instanceof Map<?, ?> memoryMap) {
            Object windowCfg = memoryMap.get("window");
            if (windowCfg instanceof Map<?, ?> windowMap) {
                boolean enabled = readBool(windowMap.get("enabled"), true);
                if (!enabled) return 0;
                int size = readInt(windowMap.get("size"), DEFAULT_MEMORY_WINDOW);
                return Math.max(0, size);
            }
            // Bare `memory.windows: N` (used by several other designer cards).
            int fallback = readInt(memoryMap.get("windows"), 0);
            if (fallback > 0) return fallback;
        }
        Object flatEnabled = node.get("memoryEnabled");
        if (flatEnabled != null && !readBool(flatEnabled, false)) return 0;
        int flatWindow = node.getInt("memoryWindow", 0);
        if (flatWindow > 0) return flatWindow;
        if (flatEnabled != null && readBool(flatEnabled, false)) {
            return DEFAULT_MEMORY_WINDOW;
        }
        return 0;
    }

    private static boolean readBool(Object v, boolean fallback) {
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return fallback;
        return switch (s) {
            case "true", "1", "yes", "on", "enable", "enabled" -> true;
            case "false", "0", "no", "off", "disable", "disabled" -> false;
            default -> fallback;
        };
    }

    private static int readInt(Object v, int fallback) {
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Message toMessage(WorkflowConversationMemory.ConversationTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isEmpty()) return null;
        String role = turn.role() == null ? "user" : turn.role().trim().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "system" -> new SystemMessage(turn.content());
            case "assistant", "ai", "model" -> new AssistantMessage(turn.content());
            // TOOL messages don't slot into a plain history injection cleanly; render
            // them as a user-turn narrative so the model still sees the observation
            // rather than dropping it silently.
            case "tool" -> new UserMessage("[tool] " + turn.content());
            default -> new UserMessage(turn.content());
        };
    }
}
