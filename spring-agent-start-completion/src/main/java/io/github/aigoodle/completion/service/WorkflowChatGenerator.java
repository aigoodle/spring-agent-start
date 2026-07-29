package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.common.SseBridge;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import io.github.aigoodle.workflow.chat.ChatStreamSink;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.node.StepRecord;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a workflow-mode / chatflow-mode app end-to-end and produces either a
 * blocking answer or a stream of Dify-parity SSE events for the visual chat UI.
 */
public class WorkflowChatGenerator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowChatGenerator.class);

    private final WorkflowService workflowService;
    private final AgentMemory memory;

    public WorkflowChatGenerator(WorkflowService workflowService, AgentMemory memory) {
        this.workflowService = workflowService;
        this.memory = memory;
    }

    public OpenAIChatResponse generateBlocking(AgentEntity app, OpenAIChatRequest req) {
        String workflowId = resolveWorkflowId(app, req);
        String conversationId = ensureConversationId(req);
        Map<String, Object> inputs = buildInputs(req, conversationId);
        WorkflowRunResult result = workflowService.run(workflowId, inputs, conversationId);
        if (!result.isSuccess()) {
            throw new AgentException("workflow_failed",
                    result.getError() == null ? "Workflow failed" : result.getError(), null);
        }
        String answer = extractAnswer(result);
        appendHistory(app.getId(), conversationId, req.lastUserMessage(), answer);
        return OpenAIChatResponse.completion(req.getModel(), answer);
    }

    public void generateStream(AgentEntity app, OpenAIChatRequest req, SseBridge.Emit emit) {
        String workflowId = app.getId();
        if (!Boolean.TRUE.equals(req.getDebug())) {
            workflowId = resolveWorkflowId(app, req);
        }
        String conversationId = ensureConversationId(req);
        Map<String, Object> inputs = buildInputs(req, conversationId);
        String taskId = "task-" + UUID.randomUUID();
        String chunkId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long startedAt = System.currentTimeMillis();

        emit.event("workflow_started", Map.of(
                "task_id", taskId,
                "workflow_id", workflowId,
                "app_id", app.getId(),
                "conversation_id", conversationId,
                "created_at", startedAt / 1000));

        final String modelName = req.getModel();
        emit.event("message", OpenAIChatResponse.chunk(chunkId, modelName, "assistant", null, null));
        ChatStreamSink sink = new ChatStreamSink(delta ->
                emit.event("message", OpenAIChatResponse.chunk(chunkId, modelName, null, delta, null)));

        WorkflowRunResult result;
        try {
            result = workflowService.run(workflowId, inputs, conversationId,
                    step -> emitNodeFinished(emit, taskId, step), sink);
        } catch (Exception ex) {
            log.warn("Workflow chat run failed for app {}: {}", app.getId(), ex.getMessage());
            sink.close();
            emit.event("error", Map.of(
                    "task_id", taskId,
                    "message", ex.getMessage() == null ? "workflow run failed" : ex.getMessage()));
            emit.event("message_end", Map.of(
                    "task_id", taskId,
                    "status", "failed"));
            return;
        }

        String answer = extractAnswer(result);
        if (sink.accumulated().isEmpty() && answer != null && !answer.isEmpty()) {
            emit.event("message", OpenAIChatResponse.chunk(chunkId, modelName, null, answer, null));
        }
        emit.event("message", OpenAIChatResponse.chunk(chunkId, modelName, null, null,
                result.isSuccess() ? "stop" : "error"));

        if (result.isSuccess()) {
            String streamed = sink.accumulated();
            String persistedAnswer = (streamed != null && !streamed.isEmpty()) ? streamed : answer;
            appendHistory(app.getId(), conversationId, req.lastUserMessage(), persistedAnswer);
        }

        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("task_id", taskId);
        finished.put("workflow_id", workflowId);
        finished.put("conversation_id", conversationId);
        finished.put("status", result.isSuccess() ? "succeeded" : "failed");
        finished.put("outputs", result.getOutputs());
        finished.put("error", result.getError());
        finished.put("elapsed_ms", System.currentTimeMillis() - startedAt);
        finished.put("total_steps", result.getSteps() == null ? 0 : result.getSteps().size());
        emit.event("workflow_finished", finished);

        emit.event("message_end", Map.of(
                "task_id", taskId,
                "status", result.isSuccess() ? "succeeded" : "failed"));
    }

    private void appendHistory(String appId, String conversationId, String userQuery, String answer) {
        if (memory == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            if (userQuery != null && !userQuery.isEmpty()) {
                memory.append(conversationId, appId, AgentMessage.user(userQuery));
            }
            if (answer != null && !answer.isEmpty()) {
                memory.append(conversationId, appId, AgentMessage.assistant(answer));
            }
        } catch (Exception ex) {
            log.debug("workflow chat history write skipped: {}", ex.getMessage());
        }
    }

    private static void emitNodeFinished(SseBridge.Emit emit, String taskId, StepRecord step) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("node_id", step.getNodeId());
        payload.put("node_type", step.getNodeType() == null ? null : step.getNodeType().name());
        payload.put("title", step.getTitle());
        payload.put("outputs", step.getOutputs());
        payload.put("handle", step.getHandle());
        payload.put("elapsed_ms", step.getElapsedMillis());
        payload.put("failed", step.isFailed());
        payload.put("error", step.getError());
        emit.event("node_finished", payload);
    }

    private static String resolveWorkflowId(AgentEntity app, OpenAIChatRequest req) {
        if (req != null) {
            String override = req.getWorkflowId();
            if (override != null && !override.isBlank()) {
                log.debug("Workflow id override → {} (app {})", override, app.getId());
                return override;
            }
            if (Boolean.TRUE.equals(req.getDebug())) {
                log.debug("Debug mode → forcing draft workflow (id={}) for app {}", app.getId(), app.getId());
                return app.getId();
            }
        }
        String workflowId = app.getWorkflowId();
        if (workflowId == null || workflowId.isBlank()) {
            workflowId = app.getId();
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new AgentException("workflow_id_missing",
                    "App " + app.getId() + " has no workflow bound — publish a workflow first.", null);
        }
        return workflowId;
    }

    private static String ensureConversationId(OpenAIChatRequest req) {
        String conversationId = req.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
            req.setConversationId(conversationId);
        }
        return conversationId;
    }

    private static Map<String, Object> buildInputs(OpenAIChatRequest req, String conversationId) {
        Map<String, Object> inputs = new HashMap<>();
        if (req.getData() != null) {
            inputs.putAll(req.getData());
        }
        inputs.put("query", req.lastUserMessage());
        inputs.put("conversation_id", conversationId);
        return inputs;
    }

    private static String extractAnswer(WorkflowRunResult result) {
        Map<String, Object> outputs = result.getOutputs();
        if (outputs == null || outputs.isEmpty()) {
            return "";
        }
        for (String key : new String[]{"answer", "text", "output", "result"}) {
            Object v = outputs.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        if (outputs.size() == 1) {
            Object v = outputs.values().iterator().next();
            return v == null ? "" : String.valueOf(v);
        }
        return String.valueOf(outputs);
    }
}
