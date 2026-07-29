package io.github.aigoodle.completion.service;

import io.github.aigoodle.completion.common.SseBridge;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import io.github.aigoodle.completion.dto.openai.OpenAIChoice;
import io.github.aigoodle.completion.dto.openai.OpenAIDelta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 {@link AgentChatGenerator} / {@link WorkflowChatGenerator} 内部使用的
 * "OpenAI chunk" 事件流实时翻译成 Dify {@code /v1/chat-messages} SSE 事件流。
 */
final class DifyEmitAdapter implements SseBridge.Emit {

    private final SseBridge.Emit delegate;
    private final String taskId;
    private final String conversationId;
    private final String messageId;

    private boolean sawFirstMessage = false;

    DifyEmitAdapter(SseBridge.Emit delegate, String taskId, String conversationId) {
        this.delegate = delegate;
        this.taskId = taskId;
        this.conversationId = conversationId;
        this.messageId = UUID.randomUUID().toString();
    }

    @Override
    public void event(String name, Object data) {
        if (name == null) {
            return;
        }
        switch (name) {
            case "message":
                translateMessage(data);
                break;
            case "workflow_started":
                emitWithEvent("workflow_started", data);
                break;
            case "workflow_finished":
                emitWithEvent("workflow_finished", data);
                break;
            case "node_started":
                emitWithEvent("node_started", data);
                break;
            case "node_finished":
                emitWithEvent("node_finished", data);
                break;
            case "chat_started":
                emitWithEvent("workflow_started", data);
                break;
            case "chat_finished":
                emitWithEvent("workflow_finished", data);
                break;
            case "step":
                emitWithEvent("agent_step", data);
                break;
            case "message_end":
                emitMessageEnd(data);
                break;
            case "error":
                emitError(data);
                break;
            default:
                emitWithEvent(name, data);
                break;
        }
    }

    private void translateMessage(Object data) {
        String delta = extractDelta(data);
        if (delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Object> payload = baseMessagePayload("message");
        payload.put("id", messageId);
        payload.put("message_id", messageId);
        payload.put("answer", delta);
        payload.put("created_at", System.currentTimeMillis() / 1000);
        delegate.event("message", payload);
        sawFirstMessage = true;
    }

    private void emitMessageEnd(Object data) {
        Map<String, Object> payload = baseMessagePayload("message_end");
        payload.put("id", messageId);
        payload.put("message_id", messageId);
        mergeIfMap(payload, data);
        if (!sawFirstMessage) {
            payload.putIfAbsent("answer", "");
        }
        delegate.event("message", payload);
    }

    private void emitError(Object data) {
        Map<String, Object> payload = baseMessagePayload("error");
        mergeIfMap(payload, data);
        payload.putIfAbsent("status", 500);
        payload.putIfAbsent("code", "internal_server_error");
        delegate.event("message", payload);
    }

    private void emitWithEvent(String eventName, Object data) {
        Map<String, Object> payload = baseMessagePayload(eventName);
        if (data instanceof Map<?, ?> map) {
            payload.put("data", map);
        } else if (data != null) {
            payload.put("data", data);
        }
        delegate.event("message", payload);
    }

    private Map<String, Object> baseMessagePayload(String eventName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventName);
        payload.put("task_id", taskId);
        payload.put("conversation_id", conversationId);
        return payload;
    }

    private static void mergeIfMap(Map<String, Object> target, Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object k = entry.getKey();
            if (k == null) continue;
            String key = k.toString();
            if ("event".equals(key) || "task_id".equals(key) || "conversation_id".equals(key)) {
                continue;
            }
            target.putIfAbsent(key, entry.getValue());
        }
    }

    private static String extractDelta(Object data) {
        if (data instanceof OpenAIChatResponse chunk) {
            List<OpenAIChoice> choices = chunk.getChoices();
            if (choices == null || choices.isEmpty()) return null;
            OpenAIDelta delta = choices.get(0).getDelta();
            return delta == null ? null : delta.getContent();
        }
        if (data instanceof Map<?, ?> map) {
            Object choicesObj = map.get("choices");
            if (choicesObj instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> choice) {
                    Object deltaObj = choice.get("delta");
                    if (deltaObj instanceof Map<?, ?> deltaMap) {
                        Object content = deltaMap.get("content");
                        return content == null ? null : content.toString();
                    }
                }
            }
        }
        return null;
    }
}
