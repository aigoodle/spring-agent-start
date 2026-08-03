package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.model.service.PromptTemplateService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.memory.WorkflowConversationMemory;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.variable.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the ordered message list for one LLM workflow-node invocation. */
final class LlmConversationBuilder {

    private static final Logger log = LoggerFactory.getLogger(LlmConversationBuilder.class);
    private static final int MAX_MEMORY_TURNS = 50;

    private final PromptTemplateService promptTemplateService;
    private final WorkflowConversationMemory conversationMemory;

    LlmConversationBuilder(PromptTemplateService promptTemplateService,
                           WorkflowConversationMemory conversationMemory) {
        this.promptTemplateService = promptTemplateService;
        this.conversationMemory = conversationMemory;
    }

    List<Message> build(NodeDef node, ExecutionContext context) {
        List<Message> messages = new ArrayList<>();
        String systemPrompt = resolveSystemPrompt(node, context);
        if (!systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        appendConversationMemory(node, context, messages);

        String userTemplate = node.getString("userPrompt", node.getString("prompt", ""));
        String userPrompt = VariableResolver.render(userTemplate, context.getPool());
        messages.add(new UserMessage(userPrompt));
        return messages;
    }

    private String resolveSystemPrompt(NodeDef node, ExecutionContext context) {
        String templateId = node.getString("systemPromptTemplateId");
        if (templateId != null && !templateId.isBlank() && promptTemplateService != null) {
            var template = promptTemplateService.get(templateId);
            if (template != null) {
                return promptTemplateService.render(
                        template.getContent(), flattenVariables(context));
            }
        }
        return VariableResolver.render(
                node.getString("systemPrompt", ""), context.getPool());
    }

    private static Map<String, Object> flattenVariables(ExecutionContext context) {
        Map<String, Object> flattenedVariables = new HashMap<>();
        Map<String, Map<String, Object>> variablesByNamespace = context.getPool().snapshot();
        Map<String, Object> systemVariables = variablesByNamespace.get("sys");
        if (systemVariables != null) {
            flattenedVariables.putAll(systemVariables);
        }
        for (Map.Entry<String, Map<String, Object>> namespace : variablesByNamespace.entrySet()) {
            if (!"sys".equals(namespace.getKey())) {
                flattenedVariables.putAll(namespace.getValue());
            }
        }
        return flattenedVariables;
    }

    private void appendConversationMemory(NodeDef node, ExecutionContext context,
                                          List<Message> messages) {
        if (conversationMemory == null) {
            return;
        }
        String conversationId = context.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        LlmMemoryWindow memoryWindow = LlmMemoryWindow.from(node);
        if (!memoryWindow.enabled()) {
            return;
        }

        try {
            List<WorkflowConversationMemory.ConversationTurn> recentTurns =
                    conversationMemory.load(
                            conversationId, Math.min(memoryWindow.size(), MAX_MEMORY_TURNS));
            if (recentTurns == null || recentTurns.isEmpty()) {
                return;
            }
            for (WorkflowConversationMemory.ConversationTurn turn : recentTurns) {
                Message message = toMessage(turn);
                if (message != null) {
                    messages.add(message);
                }
            }
        } catch (Exception exception) {
            log.warn("Memory load skipped for node {} / conversation {}: {}",
                    node.getId(), conversationId, exception.getMessage());
        }
    }

    private static Message toMessage(WorkflowConversationMemory.ConversationTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isEmpty()) {
            return null;
        }
        String role = turn.role() == null
                ? "user" : turn.role().trim().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "system" -> new SystemMessage(turn.content());
            case "assistant", "ai", "model" -> new AssistantMessage(turn.content());
            case "tool" -> new UserMessage("[tool] " + turn.content());
            default -> new UserMessage(turn.content());
        };
    }
}
