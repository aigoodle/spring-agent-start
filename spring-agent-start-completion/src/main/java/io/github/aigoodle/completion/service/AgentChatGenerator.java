package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.common.SseBridge;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a non-flow app (mode = {@code agent} / {@code chat} / {@code completion})
 * through the agent runtime and shapes the output back into the same
 * OpenAI-compatible envelope the workflow generator emits.
 */
public class AgentChatGenerator {

    private static final Logger log = LoggerFactory.getLogger(AgentChatGenerator.class);

    private final AgentService agentService;

    public AgentChatGenerator(AgentService agentService) {
        this.agentService = agentService;
    }

    public OpenAIChatResponse generateBlocking(AgentEntity app, OpenAIChatRequest req) {
        AgentRequest ar = buildAgentRequest(req);
        AgentResponse response = agentService.run(app.getId(), ar);
        return OpenAIChatResponse.completion(req.getModel(), response.getText());
    }

    public void generateStream(AgentEntity app, OpenAIChatRequest req, SseBridge.Emit emit) {
        String taskId = "task-" + UUID.randomUUID();
        String chunkId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long startedAt = System.currentTimeMillis();

        emit.event("chat_started", Map.of(
                "task_id", taskId,
                "app_id", app.getId(),
                "conversation_id", ensureConversationId(req),
                "created_at", startedAt / 1000));

        AgentRequest ar = buildAgentRequest(req);
        emit.event("message", OpenAIChatResponse.chunk(chunkId, req.getModel(), "assistant", null, null));
        AtomicBoolean streamedAnyToken = new AtomicBoolean(false);
        AgentResponse response;
        try {
            response = agentService.run(app.getId(), ar,
                    step -> {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("task_id", taskId);
                        payload.put("kind", step.getKind() == null ? null : step.getKind().name());
                        payload.put("thought", step.getThought());
                        payload.put("action", step.getAction());
                        payload.put("action_input", step.getActionInput());
                        payload.put("observation", step.getObservation());
                        payload.put("content",
                                step.getKind() == io.github.aigoodle.agent.api.AgentStep.Kind.FINAL
                                        ? null : step.getContent());
                        emit.event("step", payload);
                    },
                    delta -> {
                        if (delta == null || delta.isEmpty()) return;
                        streamedAnyToken.set(true);
                        emit.event("message",
                                OpenAIChatResponse.chunk(chunkId, req.getModel(), null, delta, null));
                    });
        } catch (Exception ex) {
            log.warn("Agent chat run failed for app {} ({}): {}", app.getId(), app.getName(), ex.getMessage());
            String code = ex instanceof AgentException ae && ae.getCode() != null
                    ? ae.getCode() : "agent_run_failed";
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "agent run failed" : ex.getMessage();
            Map<String, Object> errPayload = new LinkedHashMap<>();
            errPayload.put("task_id", taskId);
            errPayload.put("code", code);
            errPayload.put("message", message);
            errPayload.put("app_id", app.getId());
            errPayload.put("agent", app.getName());
            emit.event("error", errPayload);
            emit.event("message_end", Map.of("task_id", taskId, "status", "failed"));
            return;
        }

        if (!streamedAnyToken.get() && response.getText() != null && !response.getText().isEmpty()) {
            emit.event("message",
                    OpenAIChatResponse.chunk(chunkId, req.getModel(), null, response.getText(), null));
        }

        String finish = switch (response.getStatus()) {
            case COMPLETED -> "stop";
            case AWAITING_APPROVAL -> "tool_calls";
            case MAX_ITERATIONS -> "length";
            case FAILED -> "error";
        };
        emit.event("message", OpenAIChatResponse.chunk(chunkId, req.getModel(), null, null, finish));

        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("task_id", taskId);
        finished.put("app_id", app.getId());
        finished.put("conversation_id", response.getConversationId());
        finished.put("status", response.getStatus() == null ? null : response.getStatus().name());
        finished.put("iterations", response.getIterations());
        finished.put("elapsed_ms", System.currentTimeMillis() - startedAt);
        finished.put("total_steps", response.getSteps() == null ? 0 : response.getSteps().size());
        finished.put("pending_approval", response.getPendingApproval());
        finished.put("error", response.getError());
        emit.event("chat_finished", finished);

        emit.event("message_end", Map.of(
                "task_id", taskId,
                "status", response.getStatus() == null ? "unknown" : response.getStatus().name().toLowerCase()));
    }

    private static AgentRequest buildAgentRequest(OpenAIChatRequest req) {
        Map<String, Object> variables = new HashMap<>();
        if (req.getData() != null) {
            variables.putAll(req.getData());
        }
        return AgentRequest.builder()
                .query(req.lastUserMessage())
                .conversationId(req.getConversationId())
                .variables(variables)
                .build();
    }

    private static String ensureConversationId(OpenAIChatRequest req) {
        String conversationId = req.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
            req.setConversationId(conversationId);
        }
        return conversationId;
    }
}
