package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.completion.common.SseBridge;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import io.github.aigoodle.workflow.chat.ChatStreamSink;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.node.StepRecord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns identifiers and SSE event formatting for one streaming workflow run. */
final class WorkflowStreamSession {

    private final AgentEntity application;
    private final OpenAIChatRequest request;
    private final WorkflowChatContext context;
    private final SseBridge.Emit emitter;
    private final String taskId = "task-" + UUID.randomUUID();
    private final String chunkId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
    private final long startedAtMillis = System.currentTimeMillis();
    private final ChatStreamSink streamSink;

    WorkflowStreamSession(AgentEntity application, OpenAIChatRequest request,
                          WorkflowChatContext context, SseBridge.Emit emitter) {
        this.application = application;
        this.request = request;
        this.context = context;
        this.emitter = emitter;
        this.streamSink = new ChatStreamSink(this::emitToken);
    }

    void start() {
        emitter.event("workflow_started", Map.of(
                "task_id", taskId,
                "workflow_id", context.workflowId(),
                "app_id", application.getId(),
                "conversation_id", context.conversationId(),
                "created_at", startedAtMillis / 1000));
        emitter.event("message", OpenAIChatResponse.chunk(
                chunkId, request.getModel(), "assistant", null, null));
    }

    ChatStreamSink sink() {
        return streamSink;
    }

    void nodeFinished(StepRecord stepRecord) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("node_id", stepRecord.getNodeId());
        payload.put("node_type", stepRecord.getNodeType() == null
                ? null : stepRecord.getNodeType().name());
        payload.put("title", stepRecord.getTitle());
        payload.put("outputs", stepRecord.getOutputs());
        payload.put("handle", stepRecord.getHandle());
        payload.put("elapsed_ms", stepRecord.getElapsedMillis());
        payload.put("failed", stepRecord.isFailed());
        payload.put("error", stepRecord.getError());
        emitter.event("node_finished", payload);
    }

    /** Emits terminal events and returns the exact answer that should be persisted. */
    String complete(WorkflowRunResult runResult) {
        String workflowAnswer = WorkflowAnswerExtractor.extract(runResult);
        String streamedAnswer = streamSink.accumulated();
        if (streamedAnswer.isEmpty() && !workflowAnswer.isEmpty()) {
            emitToken(workflowAnswer);
        }
        emitter.event("message", OpenAIChatResponse.chunk(
                chunkId,
                request.getModel(),
                null,
                null,
                runResult.isSuccess() ? "stop" : "error"));
        emitter.event("workflow_finished", finishedPayload(runResult));
        emitter.event("message_end", Map.of(
                "task_id", taskId,
                "status", runResult.isSuccess() ? "succeeded" : "failed"));
        streamSink.close();
        return streamedAnswer.isEmpty() ? workflowAnswer : streamedAnswer;
    }

    void fail(RuntimeException runFailure) {
        streamSink.close();
        emitter.event("error", Map.of(
                "task_id", taskId,
                "message", runFailure.getMessage() == null
                        ? "workflow run failed" : runFailure.getMessage()));
        emitter.event("message_end", Map.of("task_id", taskId, "status", "failed"));
    }

    private void emitToken(String token) {
        emitter.event("message", OpenAIChatResponse.chunk(
                chunkId, request.getModel(), null, token, null));
    }

    private Map<String, Object> finishedPayload(WorkflowRunResult runResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("workflow_id", context.workflowId());
        payload.put("conversation_id", context.conversationId());
        payload.put("status", runResult.isSuccess() ? "succeeded" : "failed");
        payload.put("outputs", runResult.getOutputs());
        payload.put("error", runResult.getError());
        payload.put("elapsed_ms", System.currentTimeMillis() - startedAtMillis);
        payload.put("total_steps", runResult.getSteps() == null ? 0 : runResult.getSteps().size());
        return payload;
    }
}
