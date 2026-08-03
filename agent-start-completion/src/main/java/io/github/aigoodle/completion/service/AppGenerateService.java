package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.ConversationService;
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

/** Entry point that prepares a chat request and delegates it to the application runtime. */
@Service
@ConditionalOnBean(AgentService.class)
public class AppGenerateService {

    private static final Logger log = LoggerFactory.getLogger(AppGenerateService.class);

    private final AgentService agentService;
    private final ChatRequestInitializer requestInitializer;
    private final AppChatRuntimeRouter runtimeRouter;

    public AppGenerateService(AgentService agentService,
                              ObjectProvider<WorkflowService> workflowServiceProvider,
                              ObjectProvider<ConversationService> conversationServiceProvider,
                              ObjectProvider<AgentMemory> agentMemoryProvider) {
        this.agentService = agentService;
        this.requestInitializer = new ChatRequestInitializer(conversationServiceProvider, log);
        this.runtimeRouter = new AppChatRuntimeRouter(
                new AgentChatGenerator(agentService),
                workflowServiceProvider,
                agentMemoryProvider);
    }

    public OpenAIChatResponse generateBlocking(String appId, OpenAIChatRequest request) {
        AgentEntity application = prepareRequest(appId, request);
        return runtimeRouter.generateBlocking(application, request);
    }

    public Flux<ServerSentEvent<Object>> generateStream(String appId, OpenAIChatRequest request) {
        AgentEntity application = prepareRequest(appId, request);
        return SseBridge.stream(emitter ->
                runtimeRouter.generateStream(application, request, emitter));
    }

    public Flux<ServerSentEvent<Object>> generateDifyStream(String appId, OpenAIChatRequest request) {
        AgentEntity application = prepareRequest(appId, request);
        String taskId = "task-" + UUID.randomUUID();
        return SseBridge.stream(emitter -> runtimeRouter.generateStream(
                application,
                request,
                new DifyEmitAdapter(emitter, taskId, request.getConversationId())));
    }

    private AgentEntity prepareRequest(String appId, OpenAIChatRequest request) {
        AgentEntity application = agentService.require(appId);
        requestInitializer.initialize(application, request);
        return application;
    }
}
