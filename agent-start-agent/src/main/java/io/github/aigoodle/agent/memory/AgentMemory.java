package io.github.aigoodle.agent.memory;

import io.github.aigoodle.agent.api.AgentMessage;

import java.util.List;

/**
 * Conversation memory for agents. Implementations may be in-process, JDBC-backed
 * (short-term, persisted) or vector-backed (long-term semantic recall).
 * <p>
 * The {@code agentId} passed to {@link #append(String, String, AgentMessage)} lets
 * implementations link every message back to the agent that produced it — the JDBC
 * memory uses this to answer "list conversations owned by agent X" via
 * {@link #listConversations(String, int)}. Ad-hoc runs (no persisted definition) may
 * pass {@code null}.
 */
public interface AgentMemory {

    /** The most recent {@code maxMessages} messages of a conversation, oldest first. */
    List<AgentMessage> load(String conversationId, int maxMessages);

    /**
     * Recall the messages most useful for answering {@code query}. The default returns
     * recent messages (ignoring the query); semantic implementations override this to
     * retrieve by relevance.
     */
    default List<AgentMessage> recall(String conversationId, String query, int maxMessages) {
        return load(conversationId, maxMessages);
    }

    /** Legacy append (no agent id). Kept for third-party memory implementations. */
    default void append(String conversationId, AgentMessage message) {
        append(conversationId, null, message);
    }

    void append(String conversationId, String agentId, AgentMessage message);

    default void appendAll(String conversationId, String agentId, List<AgentMessage> messages) {
        for (AgentMessage message : messages) {
            append(conversationId, agentId, message);
        }
    }

    /**
     * List a compact summary of every conversation owned by {@code agentId}, most
     * recent first, capped at {@code limit}. Default: empty (in-memory / vector
     * implementations opt in by overriding).
     */
    default List<ConversationSummary> listConversations(String agentId, int limit) {
        return List.of();
    }

    /** Compact "conversation list" row. */
    record ConversationSummary(String conversationId, String firstMessage, String updatedAt) {}
}
