package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.strategy.AgentRunContext;
import io.github.aigoodle.agent.strategy.AgentStrategyRegistry;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Coordinates agent persistence, runtime configuration and strategy execution. */
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String DEFAULT_TENANT_ID = "default";
    private static final int MAX_HISTORY_SIZE = 500;

    private final AgentMapper agentMapper;
    private final AppModelConfigService modelConfigService;
    private final ModelService modelService;
    private final AgentStrategyRegistry strategyRegistry;
    private final AgentMemory memory;
    private final ApprovalGate approvalGate;
    private final AgentCatalogUpdater catalogUpdater;
    private final AgentDefinitionFactory definitionFactory;
    private final AgentToolResolver toolResolver;

    public AgentService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                        ModelService modelService, ToolRegistry toolRegistry,
                        AgentStrategyRegistry strategyRegistry, AgentMemory memory,
                        ApprovalGate approvalGate) {
        this.agentMapper = agentMapper;
        this.modelConfigService = modelConfigService;
        this.modelService = modelService;
        this.strategyRegistry = strategyRegistry;
        this.memory = memory;
        this.approvalGate = approvalGate;
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
        return memory.load(conversationId, historySize);
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
        return runDefinition(definition, request, null, null);
    }

    public AgentResponse runDefinition(AgentDefinition definition, AgentRequest request,
                                       Consumer<AgentStep> stepListener) {
        return runDefinition(definition, request, stepListener, null);
    }

    public AgentResponse runDefinition(AgentDefinition definition, AgentRequest request,
                                       Consumer<AgentStep> stepListener,
                                       Consumer<String> tokenListener) {
        String conversationId = conversationIdOf(request);
        AgentRunContext runContext = createRunContext(
                definition, request, conversationId, stepListener, tokenListener);

        log.info("Running agent '{}' (strategy={}, tools={}) conversation={}",
                definition.getName(), definition.getStrategy(), runContext.getTools().size(), conversationId);
        AgentResponse response = strategyRegistry.get(definition.getStrategy()).run(runContext);
        response.setConversationId(conversationId);
        rememberCompletedExchange(definition, request, response, conversationId);
        return response;
    }

    private AgentRunContext createRunContext(AgentDefinition definition, AgentRequest request,
                                             String conversationId, Consumer<AgentStep> stepListener,
                                             Consumer<String> tokenListener) {
        List<AgentMessage> conversationHistory = definition.isMemoryEnabled()
                ? memory.recall(conversationId, request.getQuery(), definition.getMemoryWindow())
                : List.of();
        return AgentRunContext.builder()
                .definition(definition)
                .query(request.getQuery())
                .conversationId(conversationId)
                .history(conversationHistory)
                .chatClient(resolveChatClient(definition))
                .tools(toolResolver.resolve(definition, this::run))
                .approvalGate(approvalGate)
                .stepListener(stepListener)
                .tokenListener(tokenListener)
                .build();
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
        memory.append(conversationId, definition.getId(), AgentMessage.user(request.getQuery()));
        memory.append(conversationId, definition.getId(), AgentMessage.assistant(response.getText()));
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
