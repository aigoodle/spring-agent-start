package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.completion.common.SseBridge;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a non-flow app (mode = {@code agent} / {@code chat} / {@code completion})
 * through the agent runtime and shapes the output back into the same
 * OpenAI-compatible envelope the workflow generator emits.
 */
public class AgentChatGenerator {

    private static final Logger logger = LoggerFactory.getLogger(AgentChatGenerator.class);

    private final AgentService agentService;

    public AgentChatGenerator(AgentService agentService) {
        this.agentService = agentService;
    }

    public OpenAIChatResponse generateBlocking(AgentEntity application, OpenAIChatRequest request) {
        AgentResponse response = agentService.run(application.getId(), toAgentRequest(request));
        return OpenAIChatResponse.completion(request.getModel(), response.getText());
    }

    public void generateStream(AgentEntity application, OpenAIChatRequest request, SseBridge.Emit emitter) {
        String taskId = "task-" + UUID.randomUUID();
        String chunkId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long startedAtMillis = System.currentTimeMillis();

        emitter.event("chat_started", Map.of(
                "task_id", taskId,
                "app_id", application.getId(),
                "conversation_id", ensureConversationId(request),
                "created_at", startedAtMillis / 1000));

        AgentRequest agentRequest = toAgentRequest(request);
        emitter.event("message", OpenAIChatResponse.chunk(chunkId, request.getModel(), "assistant", null, null));
        AtomicBoolean contentWasStreamed = new AtomicBoolean(false);
        AgentResponse response;
        try {
            response = agentService.run(application.getId(), agentRequest,
                    step -> emitter.event("step", AgentStreamEventPayloads.step(taskId, step)),
                    delta -> {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        contentWasStreamed.set(true);
                        emitter.event("message",
                                OpenAIChatResponse.chunk(chunkId, request.getModel(), null, delta, null));
                    });
        } catch (RuntimeException generationFailure) {
            logger.warn("Agent chat run failed for app {} ({}): {}",
                    application.getId(), application.getName(), generationFailure.getMessage());
            emitter.event("error", AgentStreamEventPayloads.error(
                    taskId, application, generationFailure));
            emitter.event("message_end", Map.of("task_id", taskId, "status", "failed"));
            return;
        }

        if (!contentWasStreamed.get() && response.getText() != null && !response.getText().isEmpty()) {
            emitter.event("message",
                    OpenAIChatResponse.chunk(chunkId, request.getModel(), null, response.getText(), null));
        }

        emitter.event("message", OpenAIChatResponse.chunk(
                chunkId, request.getModel(), null, null, AgentStreamEventPayloads.finishReason(response)));
        emitter.event("chat_finished",
                AgentStreamEventPayloads.finished(taskId, application, response, startedAtMillis));
        emitter.event("message_end", Map.of(
                "task_id", taskId,
                "status", response.getStatus() == null
                        ? "unknown"
                        : response.getStatus().name().toLowerCase(Locale.ROOT)));
    }

    private static AgentRequest toAgentRequest(OpenAIChatRequest request) {
        Map<String, Object> variables = new HashMap<>();
        if (request.getData() != null) {
            variables.putAll(request.getData());
        }
        return AgentRequest.builder()
                .query(request.lastUserMessage())
                .conversationId(request.getConversationId())
                .variables(variables)
                .build();
    }

    private static String ensureConversationId(OpenAIChatRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
            request.setConversationId(conversationId);
        }
        return conversationId;
    }
}
