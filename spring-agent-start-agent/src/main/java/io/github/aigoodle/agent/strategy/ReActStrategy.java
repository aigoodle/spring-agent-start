package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The classic ReAct loop implemented in code (no native tool calling), so it works on
 * any chat model: the model emits Thought / Action / Action Input, we run the tool and
 * feed back an Observation, repeating until a Final Answer. Honours human-in-the-loop
 * approval for sensitive tools.
 */
public class ReActStrategy implements AgentStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReActStrategy.class);

    private static final Pattern ACTION = Pattern.compile("Action\\s*:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT =
            Pattern.compile("Action\\s*Input\\s*:\\s*(.+?)(?:\\nObservation:|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FINAL =
            Pattern.compile("Final\\s*Answer\\s*:\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THOUGHT =
            Pattern.compile("Thought\\s*:\\s*(.+?)(?:\\nAction:|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public AgentStrategyType type() {
        return AgentStrategyType.REACT;
    }

    @Override
    public AgentResponse run(AgentRunContext ctx) {
        AgentDefinition def = ctx.getDefinition();
        Map<String, AgentTool> tools = new LinkedHashMap<>();
        ctx.getTools().forEach(t -> tools.put(t.name(), t));

        AgentResponse response = new AgentResponse();
        response.setConversationId(ctx.getConversationId());

        // Per-app thinking-mode gate. When the drawer sets 思考模式=false
        // (persisted as {@code enable_thinking:false} in
        // {@code app_model_configs.configs}), we (1) tell the model in the
        // system prompt it may skip the {@code Thought:} preamble and (2)
        // strip any Thought section that still leaks into the stream.
        boolean hideThought = "disabled".equals(AgentChatOptionsFactory.resolveThinkingMode(def));

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt(def, ctx.getTools(), hideThought)));
        for (AgentMessage h : ctx.getHistory()) {
            messages.add(toMessage(h));
        }
        messages.add(new UserMessage(ctx.getQuery()));

        org.springframework.ai.chat.prompt.ChatOptions perApp = AgentChatOptionsFactory.build(def);
        for (int i = 0; i < def.getMaxIterations(); i++) {
            response.setIterations(i + 1);
            var spec = ctx.getChatClient().prompt().messages(messages);
            if (perApp != null) {
                spec = spec.options(perApp);
            }
            String content = generate(ctx, spec, hideThought);
            log.debug("ReAct iteration {} output: {}", i + 1, content);

            Matcher finalMatcher = FINAL.matcher(content);
            if (finalMatcher.find()) {
                String answer = finalMatcher.group(1).strip();
                AgentStep finalStep = AgentStep.of(AgentStep.Kind.FINAL, answer);
                response.addStep(finalStep);
                ctx.fireStep(finalStep);
                response.setText(answer);
                response.setStatus(AgentResponse.Status.COMPLETED);
                return response;
            }

            Matcher actionMatcher = ACTION.matcher(content);
            if (!actionMatcher.find()) {
                // No action and no final answer: treat the content as the answer.
                // Strip the "Thought:" preamble when thinking is disabled so the
                // persisted answer stays clean (memory + step trace also see it).
                String answer = hideThought ? stripThoughtPreamble(content).strip() : content.strip();
                AgentStep finalStep = AgentStep.of(AgentStep.Kind.FINAL, answer);
                response.setText(answer);
                response.setStatus(AgentResponse.Status.COMPLETED);
                response.addStep(finalStep);
                ctx.fireStep(finalStep);
                return response;
            }

            String action = firstLine(actionMatcher.group(1));
            String actionInput = extract(ACTION_INPUT, content, "{}");
            recordThoughtAction(response, content, action, actionInput, ctx);

            String observation;
            AgentTool tool = tools.get(action);
            if (tool == null) {
                observation = "error: unknown tool '" + action + "'. Available: " + tools.keySet();
            } else if (def.getApprovalRequiredTools().contains(action)) {
                ApprovalGate.Decision decision = ctx.getApprovalGate().review(
                        new ApprovalGate.ToolCall(def.getId(), ctx.getConversationId(), action, actionInput));
                if (decision == ApprovalGate.Decision.PENDING) {
                    return awaitingApproval(response, action, actionInput, ctx);
                }
                if (decision == ApprovalGate.Decision.DENY) {
                    observation = "Tool '" + action + "' was denied by the approver.";
                } else {
                    observation = invoke(tool, actionInput);
                }
            } else {
                observation = invoke(tool, actionInput);
            }

            AgentStep obsStep = observationStep(observation);
            response.addStep(obsStep);
            ctx.fireStep(obsStep);
            messages.add(new AssistantMessage(content));
            messages.add(new UserMessage("Observation: " + observation));
        }

        response.setStatus(AgentResponse.Status.MAX_ITERATIONS);
        response.setText("Stopped after " + def.getMaxIterations() + " iterations without a final answer.");
        return response;
    }

    /**
     * Call the LLM for one ReAct iteration, streaming tokens to
     * {@link AgentRunContext#fireToken(String)} when a listener is attached so
     * the frontend gets a real typewriter effect.
     * <p>
     * ReAct's textual protocol means we can't blindly emit every token — an
     * internal tool-calling iteration produces {@code Thought/Action/Action Input}
     * markup the user shouldn't see. Emission rules per iteration:
     * <ul>
     *   <li>Buffer tokens until we can classify the response.</li>
     *   <li>If {@code "Final Answer:"} appears first → the run is producing an
     *       answer. Emit everything after that marker (incremental as chunks
     *       arrive).</li>
     *   <li>If {@code "Action:"} appears first (or first line is {@code "Action"}
     *       without a preceding {@code Thought}) → internal iteration; don't
     *       emit anything to the user this iteration.</li>
     *   <li>If neither marker ever appears (model deviates from the format and
     *       just answers directly) → after the stream completes, emit the whole
     *       buffered content as one delta so the outer generator still ships
     *       the answer.</li>
     * </ul>
     * Blocking callers ({@code tokenListener == null}) fall back to the classic
     * {@code .call().content()} — the reactive test / CLI paths keep working
     * unchanged.
     */
    private static String generate(AgentRunContext ctx, ChatClient.ChatClientRequestSpec spec,
                                   boolean hideThought) {
        if (ctx.getTokenListener() == null) {
            String content = spec.call().content();
            return content == null ? "" : content;
        }
        StringBuilder buf = new StringBuilder();
        Mode[] mode = { Mode.UNKNOWN };
        // char index in the buffer we've already emitted to the tokenListener
        int[] emittedUpTo = { 0 };

        spec.stream().content()
                .doOnNext(delta -> {
                    if (delta == null || delta.isEmpty()) return;
                    buf.append(delta);
                    if (mode[0] == Mode.INTERNAL) {
                        return; // classified as a tool-call iteration; stay quiet
                    }
                    if (mode[0] == Mode.UNKNOWN) {
                        mode[0] = classify(buf, emittedUpTo, hideThought);
                    }
                    if (mode[0] == Mode.ANSWER) {
                        int len = buf.length();
                        if (emittedUpTo[0] < len) {
                            ctx.fireToken(buf.substring(emittedUpTo[0], len));
                            emittedUpTo[0] = len;
                        }
                    }
                })
                .blockLast();

        String content = buf.toString();
        // Fallback: the model deviated from ReAct format (no markers). This is
        // the free-form-answer case — the outer parser will treat the whole
        // content as the answer, so flush it as one delta now.
        if (mode[0] == Mode.UNKNOWN && !content.isBlank()) {
            int start = emittedUpTo[0];
            // Thinking disabled → strip the leaked "Thought:" preamble from
            // whatever we're about to flush so the user never sees it.
            if (hideThought) {
                int stripped = skipThoughtPreamble(content, start);
                if (stripped > start) start = stripped;
            }
            String tail = start < content.length() ? content.substring(start) : "";
            if (!tail.isEmpty() && !hasActionMarker(content)) {
                ctx.fireToken(tail);
            }
        }
        return content;
    }

    /**
     * States for the per-iteration streaming decision. {@code UNKNOWN} means
     * we don't know yet whether the model is producing a tool call or an answer.
     */
    private enum Mode { UNKNOWN, ANSWER, INTERNAL }

    /**
     * Hard cap on buffering time — if the model's output still hasn't produced
     * any recognisable marker by this many chars, we give up and flip to
     * ANSWER anyway. Only reached for the pathological case where the model
     * starts with something that keeps looking like {@code "Thought:"} /
     * {@code "Final Answer:"} prefix (e.g., misspelled markers). In practice
     * {@link #looksLikeReActMarker} short-circuits to ANSWER on the very first
     * non-marker character, so most direct answers stream in real time.
     */
    private static final int CLASSIFY_TIMEOUT_CHARS = 160;

    /** Known ReAct format markers — buffered while incomplete, decisive once matched. */
    private static final String[] REACT_MARKERS = { "Thought:", "Action:", "Final Answer:" };

    /**
     * Look at the running buffer and decide whether we should start emitting
     * tokens ({@link Mode#ANSWER}) or stay silent for this iteration
     * ({@link Mode#INTERNAL}). Returns {@link Mode#UNKNOWN} only while the
     * buffer could still be forming a ReAct marker; the moment it clearly
     * isn't (first non-whitespace char doesn't start any of the known
     * markers) we flip to ANSWER so streaming starts on token 1 — no more
     * "wait 2s then dump a big paragraph, then stream" UX.
     * <p>
     * When ANSWER is chosen via {@code "Final Answer:"}, {@code emittedUpTo}
     * is advanced past the marker + trailing whitespace so the first emitted
     * token is real answer content, not the marker itself.
     */
    private static Mode classify(StringBuilder buf, int[] emittedUpTo, boolean hideThought) {
        String s = buf.toString();
        int finalIdx = indexOfCaseInsensitive(s, "Final Answer:");
        int actionIdx = indexOfCaseInsensitive(s, "Action:");
        // Prefer whichever marker came first — a model that emits
        // "Final Answer: X" followed by trailing chatter still counts as ANSWER.
        if (finalIdx >= 0 && (actionIdx < 0 || finalIdx < actionIdx)) {
            int start = finalIdx + "Final Answer:".length();
            while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
                start++;
            }
            emittedUpTo[0] = start;
            return Mode.ANSWER;
        }
        if (actionIdx >= 0) {
            return Mode.INTERNAL;
        }
        // Fast path: the response clearly isn't ReAct-formatted (first non-
        // whitespace char doesn't start any of the known markers). Stream
        // immediately from position 0 — this is the Qwen / GLM / any modern
        // chat model behaviour when the query doesn't need a tool.
        if (!looksLikeReActMarker(s)) {
            return Mode.ANSWER;
        }
        // Buffer keeps matching a marker prefix but the marker itself hasn't
        // completed yet — wait for more content. Past the timeout, give up
        // (e.g., model misspelled "Though:" instead of "Thought:") and flush
        // what we have; strip the leaked preamble first when hideThought is on.
        if (s.length() >= CLASSIFY_TIMEOUT_CHARS) {
            if (hideThought) {
                emittedUpTo[0] = skipThoughtPreamble(s, emittedUpTo[0]);
            }
            return Mode.ANSWER;
        }
        return Mode.UNKNOWN;
    }

    /**
     * True when the buffer (after leading whitespace) could still be an
     * incomplete prefix of one of the {@link #REACT_MARKERS}. Returns
     * {@code false} the moment the leading text diverges from every known
     * marker — that's the "not ReAct format" signal we use to start streaming
     * immediately on the very first chunk.
     * <p>
     * Empty / whitespace-only input returns {@code true} (nothing to compare
     * yet, keep buffering one more chunk).
     */
    private static boolean looksLikeReActMarker(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return true; // only whitespace so far
        String head = s.substring(i);
        for (String marker : REACT_MARKERS) {
            int cmp = Math.min(head.length(), marker.length());
            if (head.regionMatches(true, 0, marker, 0, cmp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Skip past a leading {@code Thought:} section (starting at
     * {@code offset}, tolerating leading whitespace) and return the char index
     * where the actual answer content starts. When no such prefix is present,
     * returns {@code offset} unchanged.
     * <p>
     * Handles both formats models tend to produce:
     * <ul>
     *   <li>{@code Thought: xxx\n\nAnswer content} — stops at the blank line.</li>
     *   <li>{@code Thought: xxx\nAnswer content} — stops at the newline.</li>
     *   <li>{@code Thought: xxx} (no separator, whole content is the Thought)
     *       — treats the text after {@code "Thought:"} as the answer.</li>
     * </ul>
     */
    private static int skipThoughtPreamble(String s, int offset) {
        int i = offset;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (!s.regionMatches(true, i, "Thought:", 0, "Thought:".length())) {
            return offset;
        }
        int after = i + "Thought:".length();
        // Prefer a blank-line separator; falling back to a single newline; falling
        // back to "everything after 'Thought:' is the answer".
        int blank = s.indexOf("\n\n", after);
        if (blank >= 0) {
            int start = blank + 2;
            while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
            return start;
        }
        int nl = s.indexOf('\n', after);
        if (nl >= 0) {
            int start = nl + 1;
            while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
            return start;
        }
        int start = after;
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
        return start;
    }

    /**
     * Convenience wrapper that returns the stripped substring — used on the
     * blocking / non-streaming code path where we materialise the whole
     * response before deciding it's the answer.
     */
    private static String stripThoughtPreamble(String content) {
        if (content == null) return "";
        int start = skipThoughtPreamble(content, 0);
        return start == 0 ? content : content.substring(start);
    }

    private static boolean hasActionMarker(String s) {
        return indexOfCaseInsensitive(s, "Action:") >= 0;
    }

    private static int indexOfCaseInsensitive(String haystack, String needle) {
        int n = haystack.length();
        int m = needle.length();
        if (m == 0 || n < m) return -1;
        for (int i = 0; i <= n - m; i++) {
            if (haystack.regionMatches(true, i, needle, 0, m)) {
                return i;
            }
        }
        return -1;
    }

    private String invoke(AgentTool tool, String actionInput) {
        try {
            Map<String, Object> args = JsonUtils.parseMap(actionInput);
            if (args == null || args.isEmpty()) {
                args = Map.of("input", actionInput);
            }
            Object result = tool.execute(args);
            return result == null ? "" : String.valueOf(result);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private void recordThoughtAction(AgentResponse response, String content, String action, String actionInput,
                                     AgentRunContext ctx) {
        AgentStep step = new AgentStep();
        step.setKind(AgentStep.Kind.ACTION);
        Matcher t = THOUGHT.matcher(content);
        if (t.find()) {
            step.setThought(t.group(1).strip());
        }
        step.setAction(action);
        step.setActionInput(actionInput);
        response.addStep(step);
        ctx.fireStep(step);
    }

    private AgentStep observationStep(String observation) {
        AgentStep step = new AgentStep();
        step.setKind(AgentStep.Kind.OBSERVATION);
        step.setObservation(observation);
        return step;
    }

    private AgentResponse awaitingApproval(AgentResponse response, String action, String actionInput,
                                           AgentRunContext ctx) {
        AgentResponse.PendingApproval pending = new AgentResponse.PendingApproval();
        pending.setApprovalId(UUID.randomUUID().toString());
        pending.setToolName(action);
        pending.setToolInput(actionInput);
        response.setPendingApproval(pending);
        response.setStatus(AgentResponse.Status.AWAITING_APPROVAL);
        AgentStep approvalStep = AgentStep.of(AgentStep.Kind.APPROVAL,
                "awaiting approval for tool '" + action + "'");
        response.addStep(approvalStep);
        ctx.fireStep(approvalStep);
        return response;
    }

    private static String systemPrompt(AgentDefinition def, List<AgentTool> tools, boolean hideThought) {
        StringBuilder toolList = new StringBuilder();
        StringBuilder names = new StringBuilder();
        for (AgentTool t : tools) {
            toolList.append("- ").append(t.name()).append(": ").append(t.description()).append("\n");
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(t.name());
        }
        String instructions = def.getInstructions() == null ? "You are a helpful assistant." : def.getInstructions();
        StringBuilder sb = new StringBuilder();
        sb.append(instructions);
        if (tools.isEmpty()) {
            // No tools attached → the ReAct scaffold is pure overhead. Just
            // chat directly and never emit any format markers.
            sb.append("\n\nRespond directly to the user. Do NOT emit any ")
              .append("\"Thought:\", \"Action:\" or \"Final Answer:\" prefix.");
            return sb.toString();
        }
        sb.append("\n\nYou have access to these tools:\n").append(toolList);
        if (hideThought) {
            // 思考模式=false —— tell the model to only fall back to the ReAct
            // format when a tool is actually needed. Direct answers should
            // stream through as plain text (no Thought/Final Answer prefix).
            sb.append("\nIMPORTANT: If your response does NOT require calling any tool, ")
              .append("respond directly to the user as plain text — do NOT emit any ")
              .append("\"Thought:\", \"Action:\" or \"Final Answer:\" prefix.\n\n")
              .append("Only when a tool call is genuinely needed, use this format:\n")
              .append("Action: one of [").append(names).append("]\n")
              .append("Action Input: a compact JSON object of arguments\n")
              .append("Observation: (the tool result is provided to you)\n")
              .append("... repeat as needed ...\n")
              .append("Then answer the user directly as plain text.");
        } else {
            sb.append("\nUse EXACTLY this format:\n")
              .append("Thought: your reasoning\n")
              .append("Action: one of [").append(names).append("]\n")
              .append("Action Input: a compact JSON object of arguments\n")
              .append("Observation: (the tool result is provided to you)\n")
              .append("... repeat as needed ...\n")
              .append("When you can answer, reply:\n")
              .append("Thought: I now know the final answer\n")
              .append("Final Answer: the answer for the user");
        }
        return sb.toString();
    }

    private static Message toMessage(AgentMessage m) {
        return switch (m.role()) {
            case ASSISTANT -> new AssistantMessage(m.content());
            case SYSTEM -> new SystemMessage(m.content());
            default -> new UserMessage(m.content());
        };
    }

    private static String firstLine(String s) {
        String line = s.strip();
        int nl = line.indexOf('\n');
        return (nl >= 0 ? line.substring(0, nl) : line).strip();
    }

    private static String extract(Pattern p, String content, String fallback) {
        Matcher m = p.matcher(content);
        return m.find() ? m.group(1).strip() : fallback;
    }
}
