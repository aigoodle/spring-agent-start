package io.github.aigoodle.workflow.memory;

import java.util.List;

/**
 * Per-conversation memory backing for LLM-shaped workflow nodes (LLM,
 * QUESTION_CLASSIFIER, ...). When a bean is present, nodes whose config carries
 * a {@code memory.window.enabled = true} block prepend the last N turns of the
 * conversation to their prompt so the model can reason with prior context.
 *
 * <p>Kept as a workflow-local SPI so the module does not have to depend on
 * {@code agent-start-agent}. The web / example modules provide a bridge bean
 * that delegates to {@link io.github.aigoodle.agent.memory.AgentMemory} when
 * both modules are on the classpath. Third parties can supply their own
 * implementation the same way any other {@code NodeExecutor}-adjacent bean is
 * registered.</p>
 */
public interface WorkflowConversationMemory {

    /**
     * Return the most recent {@code max} turns of a conversation, oldest first.
     * An unknown / blank {@code conversationId} must return an empty list
     * rather than throwing — the calling node treats "no history" as "cold
     * start" and continues without additional context.
     */
    List<ConversationTurn> load(String conversationId, int max);

    /**
     * One turn as observed by a workflow node. {@code role} is a lower-case
     * OpenAI-style tag: {@code system}, {@code user}, {@code assistant} or
     * {@code tool}. Unknown roles are treated as user for prompt assembly.
     */
    record ConversationTurn(String role, String content) {}
}
