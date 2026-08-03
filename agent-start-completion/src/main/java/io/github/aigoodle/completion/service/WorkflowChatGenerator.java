package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.common.SseBridge;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs workflow applications through blocking or streaming chat transports. */
public class WorkflowChatGenerator {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowChatGenerator.class);

    private final WorkflowService workflowService;
    private final AgentMemory agentMemory;

    public WorkflowChatGenerator(WorkflowService workflowService, AgentMemory agentMemory) {
        this.workflowService = workflowService;
        this.agentMemory = agentMemory;
    }

    public OpenAIChatResponse generateBlocking(AgentEntity application, OpenAIChatRequest request) {
        WorkflowChatContext chatContext = WorkflowChatContext.resolve(application, request, logger);
        WorkflowRunResult runResult = workflowService.run(
                chatContext.workflowId(), chatContext.inputs(), chatContext.conversationId());
        requireSuccess(runResult);

        String answer = WorkflowAnswerExtractor.extract(runResult);
        appendHistory(application.getId(), chatContext.conversationId(),
                request.lastUserMessage(), answer);
        return OpenAIChatResponse.completion(request.getModel(), answer);
    }

    public void generateStream(AgentEntity application, OpenAIChatRequest request,
                               SseBridge.Emit emitter) {
        WorkflowChatContext chatContext = WorkflowChatContext.resolve(application, request, logger);
        WorkflowStreamSession streamSession = new WorkflowStreamSession(
                application, request, chatContext, emitter);
        streamSession.start();

        WorkflowRunResult runResult;
        try {
            runResult = workflowService.run(
                    chatContext.workflowId(),
                    chatContext.inputs(),
                    chatContext.conversationId(),
                    streamSession::nodeFinished,
                    streamSession.sink());
        } catch (RuntimeException runFailure) {
            logger.warn("Workflow chat run failed for app {}: {}",
                    application.getId(), runFailure.getMessage());
            streamSession.fail(runFailure);
            return;
        }

        String persistedAnswer = streamSession.complete(runResult);
        if (runResult.isSuccess()) {
            appendHistory(
                    application.getId(),
                    chatContext.conversationId(),
                    request.lastUserMessage(),
                    persistedAnswer);
        }
    }

    private void appendHistory(String appId, String conversationId,
                               String userQuery, String answer) {
        if (agentMemory == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            if (userQuery != null && !userQuery.isEmpty()) {
                agentMemory.append(conversationId, appId, AgentMessage.user(userQuery));
            }
            if (answer != null && !answer.isEmpty()) {
                agentMemory.append(conversationId, appId, AgentMessage.assistant(answer));
            }
        } catch (RuntimeException historyFailure) {
            logger.debug("Workflow chat history write skipped: {}", historyFailure.getMessage());
        }
    }

    private static void requireSuccess(WorkflowRunResult runResult) {
        if (!runResult.isSuccess()) {
            throw new AgentException(
                    "workflow_failed",
                    runResult.getError() == null ? "Workflow failed" : runResult.getError(),
                    null);
        }
    }
}
