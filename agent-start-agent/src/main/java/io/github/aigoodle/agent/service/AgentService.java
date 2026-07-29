package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.multiagent.AgentDelegationTool;
import io.github.aigoodle.agent.strategy.AgentRunContext;
import io.github.aigoodle.agent.strategy.AgentStrategyRegistry;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The enterprise agent runtime entry point: create agents, then run them with a chosen
 * strategy, persistent memory, tool access, multi-agent delegation and human approval.
 * <p>
 * Persistence is split across two tables (Dify parity):
 * <ul>
 *   <li>{@code apps} — the catalog row via {@link AgentMapper} — only what the
 *       agent-list card needs.</li>
 *   <li>{@code app_model_configs} — the 1:1 sidecar via {@link AppModelConfigService} —
 *       prompt, model overrides, agent behaviour, retrieval, forms.</li>
 * </ul>
 * All create / update / read paths in this class transparently coordinate the two;
 * callers never touch the sidecar directly.
 */
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentMapper agentMapper;
    private final AppModelConfigService modelConfigService;
    private final ModelService modelService;
    private final ToolRegistry toolRegistry;
    private final AgentStrategyRegistry strategyRegistry;
    private final AgentMemory memory;
    private final ApprovalGate approvalGate;

    public AgentService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                        ModelService modelService, ToolRegistry toolRegistry,
                        AgentStrategyRegistry strategyRegistry, AgentMemory memory, ApprovalGate approvalGate) {
        this.agentMapper = agentMapper;
        this.modelConfigService = modelConfigService;
        this.modelService = modelService;
        this.toolRegistry = toolRegistry;
        this.strategyRegistry = strategyRegistry;
        this.memory = memory;
        this.approvalGate = approvalGate;
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public AgentEntity create(CreateAgentRequest req) {
        AgentEntity entity = new AgentEntity();
        entity.setTenantId(req.getTenantId());
        applyCatalog(entity, req);
        agentMapper.insert(entity);

        // Sidecar row: the "编排" drawer payload. Prompt / model params /
        // agent behaviour all land here (Dify parity).
        AppModelConfig sidecar = AppModelConfigService.fromRequest(req);
        if (sidecar != null) {
            modelConfigService.upsert(entity.getId(), entity.getTenantId(), sidecar);
        }
        return entity;
    }

    public AgentEntity require(String id) {
        AgentEntity entity = agentMapper.selectById(id);
        if (entity == null) {
            throw new AgentException("agent_not_found", "Agent not found: " + id, null);
        }
        return entity;
    }

    public List<AgentEntity> list(String tenantId) {
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .orderByDesc(AgentEntity::getCreatedAt)
                .orderByDesc(AgentEntity::getId));
    }

    @Transactional
    public AgentEntity update(String id, CreateAgentRequest req) {
        AgentEntity entity = require(id);
        applyCatalog(entity, req);
        agentMapper.updateById(entity);

        // PATCH semantics on the sidecar: only non-null incoming fields
        // overwrite; unchanged fields keep their persisted value.
        AppModelConfig sidecar = AppModelConfigService.fromRequest(req);
        if (sidecar != null) {
            modelConfigService.upsert(id, entity.getTenantId(), sidecar);
        }
        return entity;
    }

    @Transactional
    public void delete(String id) {
        agentMapper.deleteById(id);
        modelConfigService.deleteByAppId(id);
    }

    /**
     * Read the sidecar directly. Used by the app-config drawer endpoint so the
     * front-end can hydrate the full "编排" form without going through the
     * catalog row.
     */
    public AppModelConfig getModelConfig(String appId) {
        return modelConfigService.findByAppId(appId);
    }

    /**
     * Fold the {@code app_model_configs} sidecar's behaviour fields onto the
     * catalog entity's transient mirror slots (see the {@code @TableField(exist=false)}
     * block on {@link AgentEntity}). Called on the read paths of the web layer
     * so a single {@code GET /agents/{id}} carries the whole 编排 payload — the
     * drawer used to render blank because MyBatis only surfaced the lean
     * catalog columns after the {@code apps} / {@code app_model_configs} split.
     * <p>
     * Safe to call on entities without a sidecar row — the mirror slots simply
     * stay null.
     */
    public AgentEntity enrich(AgentEntity entity) {
        if (entity == null || entity.getId() == null) return entity;
        AppModelConfig cfg = modelConfigService.findByAppId(entity.getId());
        if (cfg == null) return entity;
        entity.setInstructions(cfg.getPrePrompt());
        entity.setOpeningStatement(cfg.getOpeningStatement());
        entity.setSuggestedQuestionsJson(cfg.getSuggestedQuestionsJson());
        entity.setDatasetIdsJson(cfg.getDatasetIdsJson());
        entity.setRetrievalConfigJson(cfg.getDatasetConfigsJson());
        entity.setModelSettingsJson(cfg.getConfigs());
        entity.setStrategy(cfg.getStrategy());
        entity.setToolNamesJson(cfg.getToolNamesJson());
        entity.setApprovalToolsJson(cfg.getApprovalToolsJson());
        entity.setDelegateAgentIdsJson(cfg.getDelegateAgentIdsJson());
        entity.setMaxIterations(cfg.getMaxIterations());
        entity.setMemoryEnabled(cfg.getMemoryEnabled());
        entity.setMemoryWindow(cfg.getMemoryWindow());
        // Prefer sidecar model reference when the catalog denorm is missing
        // (drafts saved before the denorm columns landed).
        if ((entity.getModelName() == null || entity.getModelName().isBlank())
                && cfg.getModelName() != null && !cfg.getModelName().isBlank()) {
            entity.setModelName(cfg.getModelName());
        }
        if ((entity.getModelProvider() == null || entity.getModelProvider().isBlank())
                && cfg.getModelProvider() != null && !cfg.getModelProvider().isBlank()) {
            entity.setModelProvider(cfg.getModelProvider());
        }
        return entity;
    }

    /** Apply catalog-only fields to the {@code apps} row. Behaviour fields go to the sidecar. */
    private static void applyCatalog(AgentEntity entity, CreateAgentRequest req) {
        if (req.getName() != null) entity.setName(req.getName());
        if (req.getDescription() != null) entity.setDescription(req.getDescription());
        if (req.getIcon() != null) entity.setIcon(req.getIcon());
        if (req.getIconBackground() != null) entity.setIconBackground(req.getIconBackground());
        if (req.getIconType() != null) entity.setIconType(req.getIconType());
        if (req.getUseIconAsAnswerIcon() != null) entity.setUseIconAsAnswerIcon(req.getUseIconAsAnswerIcon());
        if (req.getMode() != null) entity.setMode(req.getMode());
        else if (entity.getMode() == null) entity.setMode("agent");
        if (req.getStatus() != null) entity.setStatus(req.getStatus());
        else if (entity.getStatus() == null) entity.setStatus("normal");
        if (req.getIsPublic() != null) entity.setIsPublic(req.getIsPublic());
        if (req.getEnableSite() != null) entity.setEnableSite(req.getEnableSite());
        if (req.getEnableApi() != null) entity.setEnableApi(req.getEnableApi());
        if (req.getApiRpm() != null) entity.setApiRpm(req.getApiRpm());
        if (req.getApiRph() != null) entity.setApiRph(req.getApiRph());
        entity.setPublished(req.isPublished());
        // Denormalised model reference — keeps agent-list rendering JOIN-free.
        if (req.getModelName() != null) entity.setModelName(req.getModelName());
        if (req.getModelProvider() != null) entity.setModelProvider(req.getModelProvider());
    }

    /**
     * Link this app to its draft workflow row. Called by the web layer right
     * after {@link #create(CreateAgentRequest)} when the coordinating
     * controller has minted the draft.
     */
    @Transactional
    public AgentEntity bindWorkflowId(String appId, String workflowId) {
        AgentEntity entity = require(appId);
        entity.setWorkflowId(workflowId);
        agentMapper.updateById(entity);
        return entity;
    }

    /** Read history messages for a conversation, oldest first, capped at {@code max}. */
    public List<AgentMessage> history(String conversationId, int max) {
        return memory.load(conversationId, Math.min(500, Math.max(1, max)));
    }

    /**
     * Materialise the runtime definition by combining the catalog row with the
     * sidecar. Missing sidecar (legacy row or workflow-mode app) is silently
     * treated as "no overrides" so callers keep working.
     */
    public AgentDefinition toDefinition(AgentEntity e) {
        AppModelConfig cfg = modelConfigService.findByAppId(e.getId());
        String provider = firstNonBlank(cfg == null ? null : cfg.getModelProvider(), e.getModelProvider());
        String modelName = firstNonBlank(cfg == null ? null : cfg.getModelName(), e.getModelName());
        String strategyRaw = cfg == null ? null : cfg.getStrategy();
        AgentStrategyType strategy = strategyRaw == null ? AgentStrategyType.REACT
                : AgentStrategyType.valueOf(strategyRaw);
        Integer maxIter = cfg == null ? null : cfg.getMaxIterations();
        Boolean memoryEnabled = cfg == null ? null : cfg.getMemoryEnabled();
        Integer memoryWindow = cfg == null ? null : cfg.getMemoryWindow();
        return AgentDefinition.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .name(e.getName())
                .instructions(cfg == null ? null : cfg.getPrePrompt())
                .modelName(modelName)
                .modelProvider(provider)
                .strategy(strategy)
                .toolNames(cfg == null ? List.of() : JsonUtils.parseList(cfg.getToolNamesJson(), String.class))
                .approvalRequiredTools(cfg == null ? new HashSet<>()
                        : new HashSet<>(JsonUtils.parseList(cfg.getApprovalToolsJson(), String.class)))
                .delegateAgentIds(cfg == null ? List.of()
                        : JsonUtils.parseList(cfg.getDelegateAgentIdsJson(), String.class))
                .maxIterations(maxIter == null ? 6 : maxIter)
                .memoryEnabled(!Boolean.FALSE.equals(memoryEnabled))
                .memoryWindow(memoryWindow == null ? 20 : memoryWindow)
                .modelSettings(parseModelSettings(cfg == null ? null : cfg.getConfigs()))
                .build();
    }

    private static Map<String, Object> parseModelSettings(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        Map<String, Object> parsed = JsonUtils.parseMap(json);
        return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    // --------------------------------------------------------------- running

    public AgentResponse run(String agentId, AgentRequest request) {
        return runDefinition(toDefinition(require(agentId)), request, null);
    }

    /** Streaming overload: {@code stepListener} is called synchronously for each finalised step. */
    public AgentResponse run(String agentId, AgentRequest request,
                             java.util.function.Consumer<io.github.aigoodle.agent.api.AgentStep> stepListener) {
        return runDefinition(toDefinition(require(agentId)), request, stepListener, null);
    }

    /**
     * Full-streaming overload: emits each finalised step via {@code stepListener}
     * AND each token delta of the model's final answer via {@code tokenListener}.
     * Enables real typewriter SSE output on the chat UI.
     */
    public AgentResponse run(String agentId, AgentRequest request,
                             java.util.function.Consumer<io.github.aigoodle.agent.api.AgentStep> stepListener,
                             java.util.function.Consumer<String> tokenListener) {
        return runDefinition(toDefinition(require(agentId)), request, stepListener, tokenListener);
    }

    /** Run an ad-hoc agent definition (no persistence of the definition itself). */
    public AgentResponse runDefinition(AgentDefinition def, AgentRequest request) {
        return runDefinition(def, request, null, null);
    }

    public AgentResponse runDefinition(AgentDefinition def, AgentRequest request,
                                       java.util.function.Consumer<io.github.aigoodle.agent.api.AgentStep> stepListener) {
        return runDefinition(def, request, stepListener, null);
    }

    public AgentResponse runDefinition(AgentDefinition def, AgentRequest request,
                                       java.util.function.Consumer<io.github.aigoodle.agent.api.AgentStep> stepListener,
                                       java.util.function.Consumer<String> tokenListener) {
        String conversationId = request.getConversationId() != null
                ? request.getConversationId() : UUID.randomUUID().toString();

        List<AgentMessage> history = def.isMemoryEnabled()
                ? memory.recall(conversationId, request.getQuery(), def.getMemoryWindow()) : List.of();

        ChatClient chatClient = resolveChatClient(def);
        AgentRunContext ctx = AgentRunContext.builder()
                .definition(def)
                .query(request.getQuery())
                .conversationId(conversationId)
                .history(history)
                .chatClient(chatClient)
                .tools(resolveTools(def))
                .approvalGate(approvalGate)
                .stepListener(stepListener)
                .tokenListener(tokenListener)
                .build();

        log.info("Running agent '{}' (strategy={}, tools={}) conversation={}",
                def.getName(), def.getStrategy(), ctx.getTools().size(), conversationId);
        AgentResponse response = strategyRegistry.get(def.getStrategy()).run(ctx);
        response.setConversationId(conversationId);

        if (def.isMemoryEnabled() && response.getStatus() == AgentResponse.Status.COMPLETED) {
            // Tag every persisted message with the owning app id so the console's
            // "list conversations for app X" query has a discriminator — without
            // it, JdbcAgentMemory.listConversations(agentId) returns nothing.
            memory.append(conversationId, def.getId(), AgentMessage.user(request.getQuery()));
            memory.append(conversationId, def.getId(), AgentMessage.assistant(response.getText()));
        }
        return response;
    }

    private ChatClient resolveChatClient(AgentDefinition def) {
        String provider = def.getModelProvider();
        String name = def.getModelName();
        if (provider == null || provider.isBlank() || name == null || name.isBlank()) {
            throw new AgentException("model_not_configured",
                    "Agent '" + def.getName() + "' has no model configured (provider + name required)",
                    null);
        }
        return modelService.getChatClient(def.getTenantId(), provider, name);
    }

    private List<AgentTool> resolveTools(AgentDefinition def) {
        List<AgentTool> tools = new ArrayList<>();
        if (def.getToolNames() == null || def.getToolNames().isEmpty()) {
            tools.addAll(toolRegistry.all());
        } else {
            for (String name : def.getToolNames()) {
                if (toolRegistry.has(name)) {
                    tools.add(toolRegistry.get(name));
                }
            }
        }
        // multi-agent: each delegate sub-agent becomes a tool
        for (String subId : def.getDelegateAgentIds()) {
            AgentEntity sub = agentMapper.selectById(subId);
            if (sub == null) {
                continue;
            }
            AppModelConfig subCfg = modelConfigService.findByAppId(subId);
            String subName = sub.getName() == null ? subId : sub.getName();
            String safeName = "delegate_to_" + subName.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
            String description = "Delegate a subtask to the '" + subName + "' agent. "
                    + (subCfg == null || subCfg.getPrePrompt() == null ? "" : subCfg.getPrePrompt());
            tools.add(new AgentDelegationTool(safeName, description, subId, this::run));
        }
        return tools;
    }
}
