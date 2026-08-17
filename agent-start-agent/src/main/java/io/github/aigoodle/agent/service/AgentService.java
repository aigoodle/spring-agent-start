package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.context.AgentContextEngine;
import io.github.aigoodle.agent.context.AgentContextRequest;
import io.github.aigoodle.agent.context.DefaultAgentContextEngine;
import io.github.aigoodle.agent.config.AgentProperties;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.agent.runtime.AgentRuntime;
import io.github.aigoodle.agent.runtime.AgentRunEvent;
import io.github.aigoodle.agent.runtime.AgentRunSnapshot;
import io.github.aigoodle.agent.runtime.AgentRunStatus;
import io.github.aigoodle.agent.runtime.AgentRunObserver;
import io.github.aigoodle.agent.runtime.AgentRunObservation;
import io.github.aigoodle.agent.runtime.AgentRunStore;
import io.github.aigoodle.agent.runtime.InMemoryAgentRunStore;
import io.github.aigoodle.agent.runtime.AgentResumeCommand;
import io.github.aigoodle.agent.strategy.AgentRunContext;
import io.github.aigoodle.agent.strategy.AgentStrategyRegistry;
import io.github.aigoodle.agent.strategy.ResumableAgentStrategy;
import io.github.aigoodle.agent.strategy.AgentRunInterruptedException;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.memory.MemoryItem;
import io.github.aigoodle.memory.MemoryManager;
import io.github.aigoodle.memory.MemoryRole;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.tool.execution.ToolExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.Duration;

/** Coordinates agent persistence, runtime configuration and strategy execution. */
public class AgentService implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String DEFAULT_TENANT_ID = "default";
    private static final int MAX_HISTORY_SIZE = 500;

    private final AgentMapper agentMapper;
    private final AppModelConfigService modelConfigService;
    private final ModelService modelService;
    private final AgentStrategyRegistry strategyRegistry;
    private final MemoryManager memory;
    private final ApprovalGate approvalGate;
    private final AgentCatalogUpdater catalogUpdater;
    private final AgentDefinitionFactory definitionFactory;
    private final AgentToolResolver toolResolver;
    private final AgentRunStore runStore;
    private final ToolExecutionGateway toolExecutionGateway;
    private final AgentContextEngine contextEngine;
    private final List<AgentRunObserver> runObservers;
    private final ConcurrentHashMap<String, Thread> activeRuns = new ConcurrentHashMap<>();

    public AgentService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                        ModelService modelService, ToolRegistry toolRegistry,
                        AgentStrategyRegistry strategyRegistry, MemoryManager memory,
                        ApprovalGate approvalGate) {
        this(agentMapper, modelConfigService, modelService, toolRegistry, strategyRegistry,
                memory, approvalGate, new InMemoryAgentRunStore(), ToolExecutionGateway.direct(),
                new DefaultAgentContextEngine(memory, new AgentProperties()), List.of());
    }

    public AgentService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                        ModelService modelService, ToolRegistry toolRegistry,
                        AgentStrategyRegistry strategyRegistry, MemoryManager memory,
                        ApprovalGate approvalGate, AgentRunStore runStore) {
        this(agentMapper, modelConfigService, modelService, toolRegistry, strategyRegistry,
                memory, approvalGate, runStore, ToolExecutionGateway.direct(),
                new DefaultAgentContextEngine(memory, new AgentProperties()), List.of());
    }

    public AgentService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                        ModelService modelService, ToolRegistry toolRegistry,
                        AgentStrategyRegistry strategyRegistry, MemoryManager memory,
                        ApprovalGate approvalGate, AgentRunStore runStore,
                        ToolExecutionGateway toolExecutionGateway) {
        this(agentMapper, modelConfigService, modelService, toolRegistry, strategyRegistry,
                memory, approvalGate, runStore, toolExecutionGateway,
                new DefaultAgentContextEngine(memory, new AgentProperties()), List.of());
    }

    public AgentService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                        ModelService modelService, ToolRegistry toolRegistry,
                        AgentStrategyRegistry strategyRegistry, MemoryManager memory,
                        ApprovalGate approvalGate, AgentRunStore runStore,
                        ToolExecutionGateway toolExecutionGateway,
                        AgentContextEngine contextEngine) {
        this(agentMapper, modelConfigService, modelService, toolRegistry, strategyRegistry,
                memory, approvalGate, runStore, toolExecutionGateway, contextEngine, List.of());
    }

    public AgentService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                        ModelService modelService, ToolRegistry toolRegistry,
                        AgentStrategyRegistry strategyRegistry, MemoryManager memory,
                        ApprovalGate approvalGate, AgentRunStore runStore,
                        ToolExecutionGateway toolExecutionGateway,
                        AgentContextEngine contextEngine, List<AgentRunObserver> runObservers) {
        this.agentMapper = agentMapper;
        this.modelConfigService = modelConfigService;
        this.modelService = modelService;
        this.strategyRegistry = strategyRegistry;
        this.memory = memory;
        this.approvalGate = approvalGate;
        this.runStore = runStore;
        this.toolExecutionGateway = toolExecutionGateway;
        this.contextEngine = contextEngine;
        this.runObservers = runObservers == null ? List.of() : List.copyOf(runObservers);
        this.catalogUpdater = new AgentCatalogUpdater();
        this.definitionFactory = new AgentDefinitionFactory(modelConfigService);
        this.toolResolver = new AgentToolResolver(agentMapper, modelConfigService, toolRegistry);
    }

    @Transactional
    public AgentEntity create(CreateAgentRequest request) {
        AgentEntity agent = new AgentEntity();
        agent.setTenantId(valueOrDefault(request.getTenantId(), DEFAULT_TENANT_ID));
        catalogUpdater.applyRequest(request, agent);
        agentMapper.insert(agent);
        saveModelConfig(agent, request);
        return agent;
    }

    public AgentEntity require(String agentId) {
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new AgentException("agent_not_found", "Agent not found: " + agentId, null);
        }
        return agent;
    }

    public List<AgentEntity> list(String tenantId) {
        String effectiveTenant = valueOrDefault(tenantId, DEFAULT_TENANT_ID);
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getTenantId, effectiveTenant)
                .orderByDesc(AgentEntity::getCreatedAt)
                .orderByDesc(AgentEntity::getId));
    }

    /** Resolve an internal app by stable code, preferring a tenant-owned override. */
    public AgentEntity requireVisibleByCode(String appCode, String executionTenantId,
                                            String rootTenantId) {
        String code = valueOrDefault(appCode, "").trim().toLowerCase(java.util.Locale.ROOT);
        if (code.isEmpty()) {
            throw new AgentException("app_code_required", "应用编码不能为空", null);
        }
        String tenant = valueOrDefault(executionTenantId, DEFAULT_TENANT_ID);
        AgentEntity owned = findByTenantAndCode(tenant, code);
        if (owned != null) {
            return requireRunnable(owned);
        }
        String root = valueOrDefault(rootTenantId, "root");
        AgentEntity shared = findByTenantAndCode(root, code);
        if (shared == null || !"GLOBAL".equalsIgnoreCase(shared.getVisibility())) {
            throw new AgentException("app_not_found", "应用不存在或无权访问", null);
        }
        return requireRunnable(shared);
    }

    private AgentEntity findByTenantAndCode(String tenantId, String appCode) {
        return agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getTenantId, tenantId)
                .eq(AgentEntity::getAppCode, appCode)
                .last("LIMIT 1"));
    }

    private static AgentEntity requireRunnable(AgentEntity app) {
        if (!Boolean.TRUE.equals(app.getPublished())
                || "disabled".equalsIgnoreCase(app.getStatus())) {
            throw new AgentException("app_unavailable", "应用尚未发布或已停用", null);
        }
        return app;
    }

    @Transactional
    public AgentEntity update(String agentId, CreateAgentRequest request) {
        AgentEntity agent = require(agentId);
        catalogUpdater.applyRequest(request, agent);
        agentMapper.updateById(agent);
        saveModelConfig(agent, request);
        return agent;
    }

    @Transactional
    public void delete(String agentId) {
        modelConfigService.deleteByAppId(agentId);
        agentMapper.deleteById(agentId);
    }

    public AppModelConfig getModelConfig(String appId) {
        return modelConfigService.findByAppId(appId);
    }

    public AgentEntity enrich(AgentEntity agent) {
        return definitionFactory.enrich(agent);
    }

    @Transactional
    public AgentEntity bindWorkflowId(String appId, String workflowId) {
        AgentEntity agent = require(appId);
        agent.setWorkflowId(workflowId);
        agentMapper.updateById(agent);
        return agent;
    }

    public List<AgentMessage> history(String conversationId, int requestedSize) {
        int historySize = Math.min(MAX_HISTORY_SIZE, Math.max(1, requestedSize));
        return toAgentMessages(memory.history(DEFAULT_TENANT_ID, null, conversationId, historySize));
    }

    public AgentDefinition toDefinition(AgentEntity agent) {
        return definitionFactory.create(agent);
    }

    public AgentResponse run(String agentId, AgentRequest request) {
        return run(agentId, request, null, null);
    }

    public AgentResponse run(String agentId, AgentRequest request, Consumer<AgentStep> stepListener) {
        return run(agentId, request, stepListener, null);
    }

    public AgentResponse run(String agentId, AgentRequest request, Consumer<AgentStep> stepListener,
                             Consumer<String> tokenListener) {
        return runDefinition(toDefinition(require(agentId)), request, stepListener, tokenListener);
    }

    public AgentResponse runDefinition(AgentDefinition definition, AgentRequest request) {
        return run(definition, request, null, null);
    }

    public AgentResponse runDefinition(AgentDefinition definition, AgentRequest request,
                                       Consumer<AgentStep> stepListener) {
        return run(definition, request, stepListener, null);
    }

    public AgentResponse runDefinition(AgentDefinition definition, AgentRequest request,
                                       Consumer<AgentStep> stepListener,
                                       Consumer<String> tokenListener) {
        return run(definition, request, stepListener, tokenListener);
    }

    @Override
    public AgentResponse run(AgentDefinition definition, AgentRequest request,
                             Consumer<AgentStep> stepListener, Consumer<String> tokenListener) {
        String conversationId = conversationIdOf(request);
        String runId = UUID.randomUUID().toString();
        runStore.create(runId, definition, request, conversationId);
        runStore.transition(runId, AgentRunStatus.RUNNING, null, null);
        Instant observationStartedAt = Instant.now();
        notifyStarted(definition, conversationId, runId, false, observationStartedAt);
        activeRuns.put(runId, Thread.currentThread());
        try {
            AgentRunContext runContext = createRunContext(
                    definition, request, conversationId, runId, stepListener, tokenListener);

            log.info("Running agent '{}' run={} (strategy={}, tools={}) conversation={}",
                    definition.getName(), runId, definition.getStrategy(),
                    runContext.getTools().size(), conversationId);
            AgentResponse response = strategyRegistry.get(definition.getStrategy()).run(runContext);
            response.setRunId(runId);
            response.setConversationId(conversationId);
            rememberCompletedExchange(definition, request, response, conversationId);
            runStore.transition(runId, statusOf(response), response, response.getError());
            notifyFinished(definition, conversationId, runId, false, observationStartedAt,
                    statusOf(response), response.getError());
            return response;
        } catch (RuntimeException exception) {
            terminateRun(runId, exception);
            AgentRunStatus status = runStore.find(runId).map(AgentRunSnapshot::status)
                    .orElse(AgentRunStatus.FAILED);
            notifyFinished(definition, conversationId, runId, false, observationStartedAt,
                    status, exception.getMessage());
            throw exception;
        } finally {
            activeRuns.remove(runId);
        }
    }

    @Override
    public Optional<AgentRunSnapshot> findRun(String runId) {
        return runStore.find(runId);
    }

    @Override
    public List<AgentRunEvent> runEvents(String runId, long afterSequence, int limit) {
        return runStore.events(runId, afterSequence, limit);
    }

    @Override
    public AgentResponse resume(String runId, AgentResumeCommand command) {
        AgentRunSnapshot pausedRun = runStore.find(runId).orElseThrow(() ->
                new AgentException("agent_run_not_found", "Agent run not found: " + runId, null));
        if (pausedRun.status() != AgentRunStatus.WAITING_APPROVAL) {
            throw new AgentException("agent_run_not_paused",
                    "Agent run " + runId + " is not waiting for approval", null);
        }
        AgentDefinition definition = JsonUtils.parse(pausedRun.definitionJson(), AgentDefinition.class);
        AgentRequest request = JsonUtils.parse(pausedRun.requestJson(), AgentRequest.class);
        AgentResponse paused = JsonUtils.parse(pausedRun.responseJson(), AgentResponse.class);
        var strategy = strategyRegistry.get(definition.getStrategy());
        if (!(strategy instanceof ResumableAgentStrategy resumable)) {
            throw new AgentException("strategy_not_resumable",
                    "Agent strategy " + definition.getStrategy() + " does not support checkpoints", null);
        }

        runStore.transition(runId, AgentRunStatus.RUNNING, null, null);
        Instant observationStartedAt = Instant.now();
        notifyStarted(definition, pausedRun.conversationId(), runId, true, observationStartedAt);
        activeRuns.put(runId, Thread.currentThread());
        try {
            AgentRunContext context = createRunContext(definition, request,
                    pausedRun.conversationId(), runId, null, null);
            AgentResponse response = resumable.resume(context, paused, command);
            response.setRunId(runId);
            response.setConversationId(pausedRun.conversationId());
            rememberCompletedExchange(definition, request, response, pausedRun.conversationId());
            runStore.transition(runId, statusOf(response), response, response.getError());
            notifyFinished(definition, pausedRun.conversationId(), runId, true,
                    observationStartedAt, statusOf(response), response.getError());
            return response;
        } catch (RuntimeException exception) {
            terminateRun(runId, exception);
            AgentRunStatus status = runStore.find(runId).map(AgentRunSnapshot::status)
                    .orElse(AgentRunStatus.FAILED);
            notifyFinished(definition, pausedRun.conversationId(), runId, true,
                    observationStartedAt, status, exception.getMessage());
            throw exception;
        } finally {
            activeRuns.remove(runId);
        }
    }

    @Override
    public AgentRunSnapshot cancel(String runId) {
        AgentRunSnapshot run = runStore.find(runId).orElseThrow(() ->
                new AgentException("agent_run_not_found", "Agent run not found: " + runId, null));
        if (run.status().isTerminal()) {
            return run;
        }
        AgentRunSnapshot cancelled = runStore.transition(runId, AgentRunStatus.CANCELLED, null,
                "Cancelled by caller");
        Thread executionThread = activeRuns.get(runId);
        if (executionThread != null && executionThread != Thread.currentThread()) {
            executionThread.interrupt();
        }
        return cancelled;
    }

    private void terminateRun(String runId, RuntimeException exception) {
        AgentRunSnapshot current = runStore.find(runId).orElse(null);
        if (current != null && !current.status().isTerminal()) {
            AgentRunStatus target = exception instanceof AgentRunInterruptedException interrupted
                    ? (interrupted.isTimedOut() ? AgentRunStatus.TIMED_OUT : AgentRunStatus.CANCELLED)
                    : AgentRunStatus.FAILED;
            runStore.transition(runId, target, null, exception.getMessage());
        }
    }

    private static AgentRunStatus statusOf(AgentResponse response) {
        return switch (response.getStatus()) {
            case COMPLETED -> AgentRunStatus.COMPLETED;
            case AWAITING_APPROVAL -> AgentRunStatus.WAITING_APPROVAL;
            case FAILED -> AgentRunStatus.FAILED;
            case MAX_ITERATIONS -> AgentRunStatus.MAX_ITERATIONS;
        };
    }

    private AgentRunContext createRunContext(AgentDefinition definition, AgentRequest request,
                                             String conversationId, String runId,
                                             Consumer<AgentStep> stepListener,
                                             Consumer<String> tokenListener) {
        List<AgentMessage> conversationHistory = contextEngine.assemble(
                new AgentContextRequest(definition, conversationId, request.getQuery(), 0)).messages();
        return AgentRunContext.builder()
                .definition(definition)
                .query(request.getQuery())
                .conversationId(conversationId)
                .runId(runId)
                .deadline(deadlineOf(request))
                .active(() -> runStore.find(runId)
                        .map(snapshot -> !snapshot.status().isTerminal())
                        .orElse(false))
                .history(conversationHistory)
                .chatClient(resolveChatClient(definition))
                .tools(toolResolver.resolve(definition, this::run))
                .approvalGate(approvalGate)
                .toolExecutionGateway(toolExecutionGateway)
                .stepListener(stepListener)
                .tokenListener(tokenListener)
                .build();
    }

    private static Instant deadlineOf(AgentRequest request) {
        Long timeoutMillis = request.getTimeoutMillis();
        return timeoutMillis == null || timeoutMillis <= 0
                ? null : Instant.now().plusMillis(timeoutMillis);
    }

    private void notifyStarted(AgentDefinition definition, String conversationId, String runId,
                               boolean resumed, Instant startedAt) {
        AgentRunObservation observation = observation(definition, conversationId, runId,
                resumed, AgentRunStatus.RUNNING, startedAt, null);
        runObservers.forEach(observer -> safely(() -> observer.onStarted(observation)));
    }

    private void notifyFinished(AgentDefinition definition, String conversationId, String runId,
                                boolean resumed, Instant startedAt, AgentRunStatus status,
                                String error) {
        AgentRunObservation observation = observation(definition, conversationId, runId,
                resumed, status, startedAt, error);
        runObservers.forEach(observer -> safely(() -> observer.onFinished(observation)));
    }

    private static AgentRunObservation observation(AgentDefinition definition,
            String conversationId, String runId, boolean resumed, AgentRunStatus status,
            Instant startedAt, String error) {
        return new AgentRunObservation(runId, definition.getTenantId(), definition.getId(),
                conversationId, definition.getStrategy(), resumed, status, startedAt,
                Duration.between(startedAt, Instant.now()), error);
    }

    private static void safely(Runnable notification) {
        try { notification.run(); } catch (RuntimeException ignored) { }
    }

    private ChatClient resolveChatClient(AgentDefinition definition) {
        String provider = definition.getModelProvider();
        String modelName = definition.getModelName();
        if (provider == null || provider.isBlank() || modelName == null || modelName.isBlank()) {
            throw new AgentException(
                    "model_not_configured",
                    "Agent '" + definition.getName() + "' has no model configured (provider + name required)",
                    null);
        }
        return modelService.getChatClient(definition.getTenantId(), provider, modelName);
    }

    private void rememberCompletedExchange(AgentDefinition definition, AgentRequest request,
                                           AgentResponse response, String conversationId) {
        if (!definition.isMemoryEnabled() || response.getStatus() != AgentResponse.Status.COMPLETED) {
            return;
        }
        memory.rememberExchange(definition.getTenantId(), definition.getId(), conversationId,
                request.getQuery(), response.getText());
    }

    private static List<AgentMessage> toAgentMessages(List<MemoryItem> items) {
        return items.stream().map(item -> new AgentMessage(toAgentRole(item.role()), item.content())).toList();
    }

    private static AgentMessage.Role toAgentRole(MemoryRole role) {
        if (role == null) return AgentMessage.Role.USER;
        try {
            return AgentMessage.Role.valueOf(role.name());
        } catch (IllegalArgumentException nonConversationRole) {
            return AgentMessage.Role.SYSTEM;
        }
    }

    private void saveModelConfig(AgentEntity agent, CreateAgentRequest request) {
        AppModelConfig modelConfig = AppModelConfigService.fromRequest(request);
        if (modelConfig != null) {
            modelConfigService.upsert(new AppModelConfigRegistration(
                    agent.getId(), agent.getTenantId(), modelConfig));
        }
    }

    private static String conversationIdOf(AgentRequest request) {
        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
            return request.getConversationId();
        }
        return UUID.randomUUID().toString();
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
