package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.api.AgentStrategyType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Input for creating an agent.
 */
@Data
@Builder
public class CreateAgentRequest {

    private String tenantId;
    private String name;
    /** One-line summary shown on the agent card. */
    private String description;
    /** Emoji ({@code "🤖"}) or icon key. */
    private String icon;
    /** Hex background tint, e.g. {@code "#EEF2FF"}. */
    private String iconBackground;
    /** Dify-parity app mode; defaults to {@code "agent"}. */
    private String mode;
    private String instructions;
    /** First message the agent posts unprompted. */
    private String openingStatement;
    /** Chip-style starter prompts (Dify parity). */
    @Builder.Default
    private List<String> suggestedQuestions = List.of();
    /** Knowledge-base ids the agent may retrieve from (Dify parity). */
    @Builder.Default
    private List<String> datasetIds = List.of();
    /** Plain vendor model name (e.g. {@code qwen3.6-plus}); no composite parsing. */
    private String modelName;
    /** Provider key that owns {@link #modelName} (e.g. {@code qwen}). */
    private String modelProvider;
    @Builder.Default
    private AgentStrategyType strategy = AgentStrategyType.REACT;
    @Builder.Default
    private List<String> toolNames = List.of();
    @Builder.Default
    private Set<String> approvalRequiredTools = Set.of();
    @Builder.Default
    private List<String> delegateAgentIds = List.of();
    @Builder.Default
    private int maxIterations = 6;
    @Builder.Default
    private boolean memoryEnabled = true;
    @Builder.Default
    private int memoryWindow = 20;
    /** Draft vs live gate; defaults to true (surfaced immediately). */
    @Builder.Default
    private boolean published = true;

    // ------------------------------------------------------ Dify parity extras
    /** {@code emoji} / {@code image}. */
    private String iconType;
    /** Reuse the app icon as the avatar for assistant replies. */
    private Boolean useIconAsAnswerIcon;
    /** Row-level status flag ({@code normal} / {@code disabled}). */
    private String status;
    private Boolean enableSite;
    private Boolean enableApi;
    private Integer apiRpm;
    private Integer apiRph;
    private Boolean isPublic;
    /** Prompt prefix injected before the user query (legacy {@code pre_prompt}). */
    private String prePrompt;
    /** {@code simple} / {@code advanced-chat} / {@code completion} etc. */
    private String promptType;
    /** Dynamic input form schema (Dify parity, opaque JSON). */
    private Object userInputForm;
    /** File upload toggles (Dify parity, opaque JSON). */
    private Object fileUpload;
    /** Retrieval override config for attached knowledge bases (opaque JSON). */
    private Object retrievalConfig;
    /**
     * Per-app model runtime overrides — the "模型设置" drawer payload
     * (temperature, topP, maxTokens, presencePenalty, frequencyPenalty, stop,
     * thinkingMode). Applied per-request by {@code AgentChatOptionsFactory};
     * unknown keys are stored verbatim and ignored at runtime.
     */
    private Object modelSettings;
}
