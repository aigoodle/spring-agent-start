package io.github.aigoodle.agent.api;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resolved, runnable configuration of an agent (decoupled from its persistence).
 */
@Data
@Builder
public class AgentDefinition {

    private String id;
    /** Owning tenant — used to resolve {@link #modelProvider}+{@link #modelName} lazily. */
    private String tenantId;
    private String name;

    /** System prompt / persona. May reference {@code {{variable}}} placeholders. */
    private String instructions;

    /** Plain vendor model name (e.g. {@code qwen3.6-plus}) the agent talks to. */
    private String modelName;

    /** Provider that owns {@link #modelName} (e.g. {@code qwen}). */
    private String modelProvider;

    @Builder.Default
    private AgentStrategyType strategy = AgentStrategyType.REACT;

    /** Names of tools (from the tool registry) this agent may use. Empty = all. */
    @Builder.Default
    private List<String> toolNames = List.of();

    /** Tools that require human approval before execution (HITL). */
    @Builder.Default
    private Set<String> approvalRequiredTools = new HashSet<>();

    /** Sub-agent ids this agent may delegate to (each exposed as a tool). */
    @Builder.Default
    private List<String> delegateAgentIds = List.of();

    @Builder.Default
    private int maxIterations = 6;

    @Builder.Default
    private boolean memoryEnabled = true;

    @Builder.Default
    private int memoryWindow = 20;

    /**
     * Per-app model runtime overrides — decoded from
     * {@code apps.model_settings_json}. Recognised keys:
     * {@code temperature, topP, maxTokens, presencePenalty, frequencyPenalty,
     * stop, thinkingMode}. Vendor-specific translation of {@code thinkingMode}
     * happens in {@code AgentChatOptionsFactory}, keyed by {@link #modelProvider}.
     */
    @Builder.Default
    private Map<String, Object> modelSettings = new HashMap<>();
}
