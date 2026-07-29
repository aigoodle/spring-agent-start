package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.common.SseBridge;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Facade that receives an OpenAI-compatible chat request, resolves the target
 * app row, and routes to the right runtime — the workflow engine for flow-mode
 * apps, the agent runtime for everything else.
 */
@Service
@ConditionalOnBean(AgentService.class)
public class AppGenerateService {

    private static final Logger log = LoggerFactory.getLogger(AppGenerateService.class);

    private final AgentService agentService;
    private final AgentChatGenerator agentChatGenerator;
    private final ObjectProvider<WorkflowService> workflowServiceProvider;
    private final ObjectProvider<ConversationService> conversationServiceProvider;
    private final ObjectProvider<AgentMemory> agentMemoryProvider;

    private volatile WorkflowChatGenerator workflowChatGenerator;

    public AppGenerateService(AgentService agentService,
                              ObjectProvider<WorkflowService> workflowServiceProvider,
                              ObjectProvider<ConversationService> conversationServiceProvider,
                              ObjectProvider<AgentMemory> agentMemoryProvider) {
        this.agentService = agentService;
        this.workflowServiceProvider = workflowServiceProvider;
        this.conversationServiceProvider = conversationServiceProvider;
        this.agentMemoryProvider = agentMemoryProvider;
        this.agentChatGenerator = new AgentChatGenerator(agentService);
    }

    public OpenAIChatResponse generateBlocking(String appId, OpenAIChatRequest req) {
        AgentEntity app = agentService.require(appId);
        ensureConversationId(req);
        ensureConversationRecord(app, req);
        if (isFlowMode(app.getMode())) {
            return workflowGenerator().generateBlocking(app, req);
        }
        return agentChatGenerator.generateBlocking(app, req);
    }

    public Flux<ServerSentEvent<Object>> generateStream(String appId, OpenAIChatRequest req) {
        AgentEntity app = agentService.require(appId);
        ensureConversationId(req);
        ensureConversationRecord(app, req);
        boolean flow = isFlowMode(app.getMode());
        return SseBridge.stream(emit -> {
            if (flow) {
                workflowGenerator().generateStream(app, req, emit);
            } else {
                agentChatGenerator.generateStream(app, req, emit);
            }
        });
    }

    public Flux<ServerSentEvent<Object>> generateDifyStream(String appId, OpenAIChatRequest req) {
        AgentEntity app = agentService.require(appId);
        ensureConversationId(req);
        ensureConversationRecord(app, req);
        boolean flow = isFlowMode(app.getMode());
        String taskId = "task-" + UUID.randomUUID();
        String conversationId = req.getConversationId();
        return SseBridge.stream(emit -> {
            DifyEmitAdapter difyEmit = new DifyEmitAdapter(emit, taskId, conversationId);
            if (flow) {
                workflowGenerator().generateStream(app, req, difyEmit);
            } else {
                agentChatGenerator.generateStream(app, req, difyEmit);
            }
        });
    }

    private static boolean isFlowMode(String mode) {
        return "workflow".equals(mode) || "chatflow".equals(mode);
    }

    private WorkflowChatGenerator workflowGenerator() {
        WorkflowChatGenerator cached = this.workflowChatGenerator;
        if (cached != null) {
            return cached;
        }
        WorkflowService workflowService = workflowServiceProvider.getIfAvailable();
        if (workflowService == null) {
            throw new AgentException("workflow_unavailable",
                    "agent-start-workflow is not on the classpath — a flow-mode app "
                            + "cannot be run. Add the workflow starter to the deployment.", null);
        }
        cached = new WorkflowChatGenerator(workflowService, agentMemoryProvider.getIfAvailable());
        this.workflowChatGenerator = cached;
        return cached;
    }

    private static void ensureConversationId(OpenAIChatRequest req) {
        if (req.getConversationId() == null || req.getConversationId().isBlank()) {
            req.setConversationId(UUID.randomUUID().toString());
        }
    }

    private void ensureConversationRecord(AgentEntity app, OpenAIChatRequest req) {
        ConversationService svc = conversationServiceProvider.getIfAvailable();
        if (svc == null) return;
        try {
            svc.ensure(req.getConversationId(), app.getId(), app.getTenantId(), req.lastUserMessage());
        } catch (Exception ex) {
            log.debug("conversation upsert skipped: {}", ex.getMessage());
        }
    }
}
