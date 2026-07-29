package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.options.ChatOptionsFactory;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.node.ExecutionContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a {@link ChatClient} from an LLM-shaped workflow node.
 *
 * <p>The console saves the picked model as a nested {@code model} object on the
 * node's data — {@code {"modelName": "qwen3.6-plus", "modelProvider": "qwen"}}.
 * Older node configs used a flat top-level {@code modelId} carrying an
 * {@code agent_model.id} UUID; both shapes are still accepted so persisted
 * graphs keep loading.</p>
 */
final class NodeModelResolver {

    private NodeModelResolver() {}

    static ChatClient resolve(NodeDef node, ExecutionContext ctx, ModelService modelService) {
        return modelService.getChatClient(resolveEntityId(node, ctx, modelService));
    }

    /**
     * Same resolution rules as {@link #resolve} but returns the raw
     * {@link ChatModel} instead of the fluent client. Used by the LLM node's
     * streaming path: {@code ChatClient.prompt().stream()} in Spring AI 1.1.2
     * wraps the response flux with {@code MessageAggregator} for tool-call
     * detection + observation callbacks, which buffers upstream tokens before
     * they reach downstream subscribers. Calling {@code chatModel.stream(prompt)}
     * directly skips that whole layer and gives us the raw chunk-by-chunk
     * emission the LLM API actually produces.
     */
    static ChatModel resolveModel(NodeDef node, ExecutionContext ctx, ModelService modelService) {
        return modelService.getChatModel(resolveEntityId(node, ctx, modelService));
    }

    /**
     * Walk the node config, find whichever locator the designer wrote, and
     * hydrate the matching {@code agent_model} row (creating one via
     * {@link ModelService#findOrMaterialize} when only the provider+name pair
     * is given). Returns the resolved entity's id so callers can pick between
     * {@link ModelService#getChatClient(String) getChatClient(id)} and
     * {@link ModelService#getChatModel(String) getChatModel(id)}.
     */
    private static String resolveEntityId(NodeDef node, ExecutionContext ctx, ModelService modelService) {
        Object nested = node.get("model");
        if (nested instanceof Map<?, ?> map) {
            String provider = str(map.get("modelProvider"));
            if (provider == null) provider = str(map.get("providerName"));
            if (provider == null) provider = str(map.get("provider"));
            String name = str(map.get("modelName"));
            if (name == null) name = str(map.get("model"));
            if (provider != null && name != null) {
                ModelEntity entity = modelService.findOrMaterialize(
                        tenantOf(ctx), provider, name, ModelType.LLM);
                return entity.getId();
            }
            // Legacy nested id (already an agent_model.id).
            String nestedId = str(map.get("modelId"));
            if (nestedId != null && !nestedId.isBlank() && !isComposite(nestedId)) {
                return nestedId;
            }
        }
        String provider = node.getString("modelProvider");
        String name = node.getString("modelName");
        if (provider != null && name != null) {
            ModelEntity entity = modelService.findOrMaterialize(
                    tenantOf(ctx), provider, name, ModelType.LLM);
            return entity.getId();
        }
        String modelId = node.getString("modelId");
        if (modelId != null && !modelId.isBlank() && !isComposite(modelId)) {
            return modelId;
        }
        throw new IllegalArgumentException(
                "Node '" + node.getId() + "' has no resolvable model (need modelProvider + modelName)");
    }

    private static boolean isComposite(String s) {
        return s.contains("::");
    }

    /** Mirror {@code ModelService#tenant()} — blank tenant defaults to {@code "default"}. */
    private static String tenantOf(ExecutionContext ctx) {
        String t = ctx == null ? null : ctx.getTenantId();
        return t == null || t.isBlank() ? "default" : t;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Translate the designer's per-node model overrides
     * ({@code model.completionParams} — Dify shape) into a Spring AI
     * {@link ChatOptions}. Delegates the vendor-specific quirks (thinking mode
     * toggles for Qwen / GLM / Doubao, {@code reasoning_effort} for o-series,
     * Ollama's native {@code enableThinking()} / {@code disableThinking()},
     * penalties, stops, etc.) to {@link ChatOptionsFactory} in
     * {@code agent-start-model} so every LLM-shaped node
     * ({@code LlmNodeExecutor}, {@code ParameterExtractorNodeExecutor}, …)
     * shares one source of truth for "how does a Dify-shape settings blob
     * become vendor params" — otherwise disabling reasoning would have to be
     * re-wired per node and drift out of sync.
     * <p>
     * Returns {@code null} when the node has no overrides so the caller can
     * skip attaching options entirely.
     */
    static ChatOptions perNodeOptions(NodeDef node) {
        Object nested = node.get("model");
        if (!(nested instanceof Map<?, ?> map)) {
            return null;
        }
        String provider = firstString(map, "modelProvider", "providerName", "provider");
        String name = firstString(map, "modelName", "model");
        Object cp = map.get("completionParams");
        if (!(cp instanceof Map<?, ?> cpMap) || cpMap.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = new HashMap<>((Map<String, Object>) cpMap);
        return ChatOptionsFactory.buildFromSettings(provider, name, settings);
    }

    private static String firstString(Map<?, ?> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }
}
