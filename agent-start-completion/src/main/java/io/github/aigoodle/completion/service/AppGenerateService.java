package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AppEntity;
import io.github.aigoodle.memory.MemoryManager;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.AppService;
import io.github.aigoodle.agent.service.AppConversationService;
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

    private final AppService appService;
    private final ChatRequestInitializer requestInitializer;
    private final AppChatRuntimeRouter runtimeRouter;

    public AppGenerateService(AppService appService,
                              AgentService agentService,
                              ObjectProvider<WorkflowService> workflowServiceProvider,
                              ObjectProvider<AppConversationService> conversationServiceProvider,
                              ObjectProvider<MemoryManager> memoryManagerProvider) {
        this.appService = appService;
        this.requestInitializer = new ChatRequestInitializer(conversationServiceProvider, log);
        this.runtimeRouter = new AppChatRuntimeRouter(
                new AgentChatGenerator(agentService),
                workflowServiceProvider,
                memoryManagerProvider);
    }

    public OpenAIChatResponse generateBlocking(String appId, OpenAIChatRequest request) {
        return generateBlocking(appId, null, request);
    }

    public OpenAIChatResponse generateBlocking(String appId, String executionTenantId,
                                               OpenAIChatRequest request) {
        AppEntity application = prepareRequest(appId, executionTenantId, request);
        return runtimeRouter.generateBlocking(application, request);
    }

    public Flux<ServerSentEvent<Object>> generateStream(String appId, OpenAIChatRequest request) {
        return generateStream(appId, null, request);
    }

    public Flux<ServerSentEvent<Object>> generateStream(String appId, String executionTenantId,
                                                        OpenAIChatRequest request) {
        AppEntity application = prepareRequest(appId, executionTenantId, request);
        return SseBridge.stream(emitter ->
                runtimeRouter.generateStream(application, request, emitter));
    }

    public Flux<ServerSentEvent<Object>> generateDifyStream(String appId, OpenAIChatRequest request) {
        AppEntity application = prepareRequest(appId, null, request);
        String taskId = "task-" + UUID.randomUUID();
        return SseBridge.stream(emitter -> runtimeRouter.generateStream(
                application,
                request,
                new DifyEmitAdapter(emitter, taskId, request.getConversationId())));
    }

    private AppEntity prepareRequest(String appId, String executionTenantId,
                                       OpenAIChatRequest request) {
        AppEntity application = appService.require(appId);
        if (executionTenantId != null && !executionTenantId.isBlank()) {
            application.setTenantId(executionTenantId);
        }
        requestInitializer.initialize(application, request);
        return application;
    }
}
