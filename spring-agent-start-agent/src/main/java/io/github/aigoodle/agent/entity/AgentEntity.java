package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The catalog row of an app / agent (Dify parity {@code apps} table).
 * <p>
 * Deliberately lean: only the fields the agent-list card and the app site need
 * to render a card. Everything about <em>how the app behaves</em> — prompt,
 * model overrides, tools, delegation, memory, retrieval, user-input form —
 * lives in the 1:1 sidecar {@link AppModelConfig} keyed by the same id.
 * <p>
 * Denormalised {@link #modelProvider} + {@link #modelName} are kept here so
 * the list view can render "provider · model" without a JOIN; the runtime
 * source of truth is still {@link AppModelConfig}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("apps")
public class AgentEntity extends BaseEntity {

    private String name;

    /** Short summary shown on the agent list card (Dify parity). */
    private String description;

    /** Emoji ({@code "🤖"}) or icon key ({@code "brain"}). */
    private String icon;

    /** Card background tint, hex string e.g. {@code "#EEF2FF"}. */
    private String iconBackground;

    /** {@code emoji} / {@code image} — how the icon should be rendered. */
    private String iconType;

    /** Reuse the app icon as the avatar for assistant replies. */
    private Boolean useIconAsAnswerIcon;

    /**
     * App mode determining the runtime path (Dify parity):
     * {@code agent} / {@code chat} / {@code workflow} / {@code chatflow} /
     * {@code completion}.
     */
    private String mode;

    /** Row-level status flag ({@code normal} / {@code disabled}). */
    private String status;

    /** Public visibility flag (surfaced in a shared explorer). */
    private Boolean isPublic;

    /** Enable the hosted chat widget / public site for this app. */
    private Boolean enableSite;

    /** Enable programmatic API access for this app. */
    private Boolean enableApi;

    /** Per-app requests-per-minute cap for the public API. {@code 0} = unlimited. */
    private Integer apiRpm;

    /** Per-app requests-per-hour cap for the public API. {@code 0} = unlimited. */
    private Integer apiRph;

    /**
     * Draft vs published (Dify parity). {@code false} = still being edited,
     * hidden from the public agent list; {@code true} = surfaced to users.
     */
    private Boolean published;

    /**
     * FK to {@code workflows.id} — the persistent DRAFT workflow this app
     * edits. Populated for workflow / chatflow mode apps; null for
     * chat / agent / completion modes.
     */
    private String workflowId;

    /**
     * Denormalised copy of {@link AppModelConfig#getModelName()} — so the
     * agent-list card renders without a sidecar JOIN. Runtime resolution goes
     * through {@code app_model_configs} first, falling back here.
     */
    private String modelName;

    /** Denormalised copy of {@link AppModelConfig#getModelProvider()}. */
    private String modelProvider;

    // ---------------------------------------------------- Sidecar mirror (transient)
    // Populated by {@code AgentService.enrich(entity)} on read paths so the
    // frontend drawer can hydrate the whole 编排 form from a single
    // {@code GET /agents/{id}} response. All fields are {@code exist=false}
    // — MyBatis writes stay confined to the {@code apps} catalog table; the
    // sidecar owns persistence via {@link AppModelConfigService}.

    /** Mirror of {@link AppModelConfig#getPrePrompt()} — the system prompt. */
    @TableField(exist = false)
    private String instructions;

    /** Mirror of {@link AppModelConfig#getOpeningStatement()}. */
    @TableField(exist = false)
    private String openingStatement;

    /** Mirror of {@link AppModelConfig#getSuggestedQuestionsJson()}. */
    @TableField(exist = false)
    private String suggestedQuestionsJson;

    /** Mirror of {@link AppModelConfig#getDatasetIdsJson()}. */
    @TableField(exist = false)
    private String datasetIdsJson;

    /** Mirror of {@link AppModelConfig#getDatasetConfigsJson()} — retrieval overrides. */
    @TableField(exist = false)
    private String retrievalConfigJson;

    /** Mirror of {@link AppModelConfig#getConfigs()} — 模型设置 payload. */
    @TableField(exist = false)
    private String modelSettingsJson;

    /** Mirror of {@link AppModelConfig#getStrategy()}. */
    @TableField(exist = false)
    private String strategy;

    /** Mirror of {@link AppModelConfig#getToolNamesJson()}. */
    @TableField(exist = false)
    private String toolNamesJson;

    /** Mirror of {@link AppModelConfig#getApprovalToolsJson()}. */
    @TableField(exist = false)
    private String approvalToolsJson;

    /** Mirror of {@link AppModelConfig#getDelegateAgentIdsJson()}. */
    @TableField(exist = false)
    private String delegateAgentIdsJson;

    /** Mirror of {@link AppModelConfig#getMaxIterations()}. */
    @TableField(exist = false)
    private Integer maxIterations;

    /** Mirror of {@link AppModelConfig#getMemoryEnabled()}. */
    @TableField(exist = false)
    private Boolean memoryEnabled;

    /** Mirror of {@link AppModelConfig#getMemoryWindow()}. */
    @TableField(exist = false)
    private Integer memoryWindow;
}
