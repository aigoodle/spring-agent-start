package io.github.aigoodle.agent.memory;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.memory.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Compatibility adapter from the agent API to the dedicated layered memory module. */
public class LayeredAgentMemory implements AgentMemory {
    private final MemoryManager manager;
    private final AgentMemory conversationArchive;

    public LayeredAgentMemory(MemoryManager manager, AgentMemory conversationArchive) {
        this.manager = manager;
        this.conversationArchive = conversationArchive;
    }

    @Override
    public List<AgentMessage> load(String conversationId, int maxMessages) {
        if (conversationArchive != null) {
            return conversationArchive.load(conversationId, maxMessages);
        }
        List<AgentMessage> messages = map(manager.recall(new MemoryQuery("default", null,
                conversationId, null, Set.of(MemoryTier.SHORT_TERM), maxMessages)));
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<AgentMessage> recall(String conversationId, String query, int maxMessages) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        return map(manager.recall(new MemoryQuery("default", null, conversationId, query,
                Set.of(MemoryTier.SHORT_TERM, MemoryTier.LONG_TERM), maxMessages)));
    }

    @Override
    public void append(String conversationId, String agentId, AgentMessage message) {
        if (conversationId == null || message == null || message.content() == null
                || message.content().isBlank()) return;
        manager.remember(MemoryWrite.shortTerm(agentId, conversationId,
                MemoryRole.valueOf(message.role().name()), message.content()));
        // Preserve the existing messages table and conversation-list API during migration.
        if (conversationArchive != null) conversationArchive.append(conversationId, agentId, message);
    }

    @Override
    public List<ConversationSummary> listConversations(String agentId, int limit) {
        return conversationArchive == null ? List.of() : conversationArchive.listConversations(agentId, limit);
    }

    private static List<AgentMessage> map(List<MemoryItem> items) {
        List<AgentMessage> messages = new ArrayList<>(items.size());
        for (MemoryItem item : items) {
            try {
                messages.add(new AgentMessage(AgentMessage.Role.valueOf(item.role().name()), item.content()));
            } catch (IllegalArgumentException factRole) {
                messages.add(AgentMessage.system("[Remembered " + item.tier() + "] " + item.content()));
            }
        }
        return messages;
    }
}
