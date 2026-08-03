package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.common.SseBridge;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.springframework.beans.factory.ObjectProvider;

/** Routes application chat to either the agent runtime or workflow engine. */
final class AppChatRuntimeRouter {

    private final AgentChatGenerator agentGenerator;
    private final ObjectProvider<WorkflowService> workflowServiceProvider;
    private final ObjectProvider<AgentMemory> agentMemoryProvider;

    private volatile WorkflowChatGenerator workflowGenerator;

    AppChatRuntimeRouter(AgentChatGenerator agentGenerator,
                         ObjectProvider<WorkflowService> workflowServiceProvider,
                         ObjectProvider<AgentMemory> agentMemoryProvider) {
        this.agentGenerator = agentGenerator;
        this.workflowServiceProvider = workflowServiceProvider;
        this.agentMemoryProvider = agentMemoryProvider;
    }

    OpenAIChatResponse generateBlocking(AgentEntity application, OpenAIChatRequest request) {
        return isFlowApplication(application)
                ? workflowGenerator().generateBlocking(application, request)
                : agentGenerator.generateBlocking(application, request);
    }

    void generateStream(AgentEntity application, OpenAIChatRequest request, SseBridge.Emit emitter) {
        if (isFlowApplication(application)) {
            workflowGenerator().generateStream(application, request, emitter);
        } else {
            agentGenerator.generateStream(application, request, emitter);
        }
    }

    static boolean isFlowApplication(AgentEntity application) {
        String mode = application.getMode();
        return "workflow".equals(mode) || "chatflow".equals(mode);
    }

    private WorkflowChatGenerator workflowGenerator() {
        WorkflowChatGenerator existingGenerator = workflowGenerator;
        if (existingGenerator != null) {
            return existingGenerator;
        }
        synchronized (this) {
            if (workflowGenerator == null) {
                workflowGenerator = createWorkflowGenerator();
            }
            return workflowGenerator;
        }
    }

    private WorkflowChatGenerator createWorkflowGenerator() {
        WorkflowService workflowService = workflowServiceProvider.getIfAvailable();
        if (workflowService == null) {
            throw new AgentException(
                    "workflow_unavailable",
                    "A flow-mode app requires agent-start-workflow on the classpath.",
                    null);
        }
        return new WorkflowChatGenerator(workflowService, agentMemoryProvider.getIfAvailable());
    }
}
