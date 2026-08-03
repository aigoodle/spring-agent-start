package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Resolved workflow, conversation and input state for one chat request. */
record WorkflowChatContext(String workflowId, String conversationId,
                           Map<String, Object> inputs) {

    static WorkflowChatContext resolve(AgentEntity application, OpenAIChatRequest request,
                                       Logger logger) {
        String conversationId = ensureConversationId(request);
        return new WorkflowChatContext(
                resolveWorkflowId(application, request, logger),
                conversationId,
                buildInputs(request, conversationId));
    }

    private static String resolveWorkflowId(AgentEntity application, OpenAIChatRequest request,
                                            Logger logger) {
        String requestedWorkflowId = request.getWorkflowId();
        if (requestedWorkflowId != null && !requestedWorkflowId.isBlank()) {
            logger.debug("Using workflow override {} for app {}", requestedWorkflowId, application.getId());
            return requestedWorkflowId;
        }
        if (Boolean.TRUE.equals(request.getDebug())) {
            logger.debug("Using draft workflow {} for app {}", application.getId(), application.getId());
            return application.getId();
        }

        String boundWorkflowId = application.getWorkflowId();
        String workflowId = boundWorkflowId == null || boundWorkflowId.isBlank()
                ? application.getId()
                : boundWorkflowId;
        if (workflowId == null || workflowId.isBlank()) {
            throw new AgentException(
                    "workflow_id_missing",
                    "App " + application.getId() + " has no workflow bound; publish a workflow first.",
                    null);
        }
        return workflowId;
    }

    private static String ensureConversationId(OpenAIChatRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
            request.setConversationId(conversationId);
        }
        return conversationId;
    }

    private static Map<String, Object> buildInputs(OpenAIChatRequest request, String conversationId) {
        Map<String, Object> inputs = new HashMap<>();
        if (request.getData() != null) {
            inputs.putAll(request.getData());
        }
        inputs.put("query", request.lastUserMessage());
        inputs.put("conversation_id", conversationId);
        return inputs;
    }
}
