package io.github.aigoodle.model.options;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a normalized, Dify-shape {@code completionParams} / {@code
 * modelSettings} map into a Spring AI {@link ChatOptions} for the configured
 * vendor. Lives in {@code agent-start-model} so both the agent runtime
 * ({@code AgentChatOptionsFactory} in {@code agent-start-agent}) and the
 * workflow LLM node ({@code LlmNodeExecutor} in {@code agent-start-workflow})
 * can share one source of truth — otherwise a new vendor toggle (say Doubao
 * adding a "thinking_budget" param) would have to be duplicated in two places
 * and inevitably drift out of sync.
 * <p>
 * The blob is vendor-neutral (temperature / topP / maxTokens / penalties /
 * stop / a normalized {@code thinkingMode}); this factory does the vendor-side
 * translation:
 * <ul>
 *   <li>{@code ollama} — native {@link OllamaChatOptions} with
 *       {@code enableThinking() / disableThinking()} for reasoning-capable
 *       models (qwen3, deepseek-r1, gpt-oss, etc.)</li>
 *   <li>{@code openai} + all OpenAI-compat vendors — {@link OpenAiChatOptions}
 *       with common fields plus an {@code extraBody} entry per vendor:
 *       <ul>
 *         <li>Qwen / DashScope → {@code enable_thinking: true|false}</li>
 *         <li>Zhipu GLM-4.5+ → {@code thinking: {type: enabled|disabled}}</li>
 *         <li>Volcengine Doubao → {@code thinking: {type: enabled|disabled}}</li>
 *         <li>OpenAI o-series → {@code reasoning_effort: low}</li>
 *       </ul>
 *   </li>
 * </ul>
 * Native SDK providers (spring-ai-zhipuai, spring-ai-deepseek) whose
 * {@code ChatModel} rejects a foreign {@code ChatOptions} type silently drop
 * the extras and only keep the merged common fields — safe by default.
 * <p>
 * Returns {@code null} when the settings map is empty so the caller can skip
 * attaching options entirely.
 */
public final class ChatOptionsFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatOptionsFactory.class);

    private ChatOptionsFactory() {}

    public static ChatOptions buildFromSettings(String providerName, String modelName,
                                                Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) return null;
        String provider = providerName == null ? "" : providerName.toLowerCase(Locale.ROOT);
        ChatOptions opts = "ollama".equals(provider)
                ? buildOllama(settings, modelName)
                : buildOpenAiCompat(provider, modelName, settings);
        if (log.isDebugEnabled() && opts != null) {
            log.debug("Built ChatOptions for provider={} model={} settings={} → {}",
                    provider, modelName, settings, describe(opts));
        }
        return opts;
    }

    /**
     * Resolve the normalized thinking mode ({@code "enabled" | "disabled" |
     * null}) from a settings map. {@code null} = "no override — vendor default
     * applies". Used by strategies that need to adapt the prompt or output
     * filter independently of the ChatOptions build (ReAct hides the
     * {@code Thought:} preamble when reasoning is disabled).
     */
    public static String resolveThinkingMode(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) return null;
        return normalizeThinkingMode(firstNonNull(settings, "thinkingMode",
                "enable_thinking", "thinking_mode"));
    }

    /** Compact one-liner for the debug log — full toString is noisy. */
    private static String describe(ChatOptions opts) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("temp=").append(opts.getTemperature());
        sb.append(", topP=").append(opts.getTopP());
        sb.append(", maxTokens=").append(opts.getMaxTokens());
        if (opts instanceof OpenAiChatOptions oa && oa.getExtraBody() != null && !oa.getExtraBody().isEmpty()) {
            sb.append(", extraBody=").append(oa.getExtraBody());
        }
        sb.append("}");
        return sb.toString();
    }

    // ---------------------------------------------------------------- OpenAI

    private static ChatOptions buildOpenAiCompat(String provider, String modelName, Map<String, Object> s) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder();
        Double temperature = firstDouble(s, "temperature");
        if (temperature != null) b.temperature(temperature);
        Double topP = firstDouble(s, "topP", "top_p");
        if (topP != null) b.topP(topP);
        Integer maxTokens = firstInt(s, "maxTokens", "max_tokens");
        if (maxTokens != null) b.maxTokens(maxTokens);
        Double presence = firstDouble(s, "presencePenalty", "presence_penalty");
        if (presence != null) b.presencePenalty(presence);
        Double frequency = firstDouble(s, "frequencyPenalty", "frequency_penalty");
        if (frequency != null) b.frequencyPenalty(frequency);
        List<String> stop = asStringList(firstNonNull(s, "stop", "stop_sequences"));
        if (stop != null && !stop.isEmpty()) b.stop(stop);

        Map<String, Object> extraBody = buildExtraBody(provider, modelName, s, b);
        if (extraBody != null && !extraBody.isEmpty()) {
            b.extraBody(extraBody);
        }
        return b.build();
    }

    /**
     * Vendor-specific translation of {@code thinkingMode} into either a raw
     * body param (OpenAI-compat vendors) or, for OpenAI itself, the standard
     * {@code reasoning_effort} field. Returns {@code null} when the caller
     * hasn't set a thinking mode or the vendor has no known toggle.
     */
    private static Map<String, Object> buildExtraBody(String provider, String modelName,
                                                     Map<String, Object> s, OpenAiChatOptions.Builder b) {
        String mode = normalizeThinkingMode(firstNonNull(s, "thinkingMode",
                "enable_thinking", "thinking_mode"));
        Map<String, Object> extras = new LinkedHashMap<>();
        Object rawExtra = firstNonNull(s, "extraBody", "extra_body");
        if (rawExtra instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                extras.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        Integer thinkingBudget = firstInt(s, "thinkingBudget", "thinking_budget");
        if (thinkingBudget != null) {
            extras.put("thinking_budget", thinkingBudget);
        }
        Boolean webSearch = asBoolean(firstNonNull(s, "enableWebSearch", "enable_web_search"));
        if (webSearch != null) {
            extras.put("enable_search", webSearch);
        }
        Integer seed = firstInt(s, "seed");
        if (seed != null) extras.put("seed", seed);
        Double repetitionPenalty = firstDouble(s, "repetitionPenalty", "repetition_penalty");
        if (repetitionPenalty != null) extras.put("repetition_penalty", repetitionPenalty);
        if (mode == null) return extras;
        switch (provider) {
            case "qwen", "dashscope" ->
                extras.put("enable_thinking", "enabled".equals(mode));
            case "zhipu", "bigmodel" ->
                extras.put("thinking", Map.of("type", mode));
            case "volcengine", "doubao", "ark" ->
                extras.put("thinking", Map.of("type", mode));
            case "openai" -> {
                if ("enabled".equals(mode)) {
                    b.reasoningEffort("medium");
                } else if ("disabled".equals(mode)) {
                    b.reasoningEffort("low");
                }
            }
            case "deepseek" -> {
                // DeepSeek toggles reasoning by model name (deepseek-reasoner
                // vs deepseek-chat) — no in-body switch. Nothing to add.
            }
            case "moonshot", "siliconflow" -> {
                // No documented thinking toggle at time of writing. Leave the
                // persisted mode alone so the console still round-trips.
            }
            default -> {
                // Unknown provider — do nothing, settings still round-trip so
                // a future provider bean can consume it.
            }
        }
        return extras;
    }

    // ---------------------------------------------------------------- Ollama

    private static ChatOptions buildOllama(Map<String, Object> s, String modelName) {
        OllamaChatOptions.Builder b = OllamaChatOptions.builder();
        Double temperature = firstDouble(s, "temperature");
        if (temperature != null) b.temperature(temperature);
        Double topP = firstDouble(s, "topP", "top_p");
        if (topP != null) b.topP(topP);
        Integer topK = firstInt(s, "topK", "top_k");
        if (topK != null && topK > 0) b.topK(topK);
        Integer maxTokens = firstInt(s, "maxTokens", "max_tokens");
        if (maxTokens != null) b.numPredict(maxTokens);
        Double presence = firstDouble(s, "presencePenalty", "presence_penalty");
        if (presence != null) b.presencePenalty(presence);
        Double frequency = firstDouble(s, "frequencyPenalty", "frequency_penalty");
        if (frequency != null) b.frequencyPenalty(frequency);
        List<String> stop = asStringList(firstNonNull(s, "stop", "stop_sequences"));
        if (stop != null && !stop.isEmpty()) b.stop(stop);

        String mode = normalizeThinkingMode(firstNonNull(s, "thinkingMode",
                "enable_thinking", "thinking_mode"));
        if ("enabled".equals(mode)) {
            b.enableThinking();
        } else if ("disabled".equals(mode)) {
            b.disableThinking();
        }
        return b.build();
    }

    // --------------------------------------------------------------- helpers

    /**
     * Accept the drawer's boolean {@code enable_thinking}, the string
     * {@code auto/on/off}, or the canonical {@code enabled/disabled}; normalize
     * to {@code enabled|disabled|null}. {@code null} means "no override" so
     * the vendor's own default applies.
     */
    private static String normalizeThinkingMode(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b ? "enabled" : "disabled";
        String v = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "auto".equals(v)) return null;
        return switch (v) {
            case "true", "on", "enable", "enabled" -> "enabled";
            case "false", "off", "disable", "disabled" -> "disabled";
            default -> null;
        };
    }

    private static Object firstNonNull(Map<String, Object> s, String... keys) {
        for (String k : keys) {
            Object v = s.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static Double firstDouble(Map<String, Object> s, String... keys) {
        Object v = firstNonNull(s, keys);
        return v == null ? null : asDouble(v);
    }

    private static Integer firstInt(Map<String, Object> s, String... keys) {
        Object v = firstNonNull(s, keys);
        return v == null ? null : asInteger(v);
    }

    private static Boolean asBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "1", "yes", "on" -> Boolean.TRUE;
            case "false", "0", "no", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static Integer asInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(v).trim()); } catch (NumberFormatException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }
}
