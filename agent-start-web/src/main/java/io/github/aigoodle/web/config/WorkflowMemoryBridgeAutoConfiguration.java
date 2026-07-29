package io.github.aigoodle.web.config;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.workflow.memory.WorkflowConversationMemory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bridges {@link AgentMemory} (agent module) into {@link WorkflowConversationMemory}
 * (workflow module) so LLM-shaped workflow nodes can inject prior conversation
 * turns from the same {@code messages} store the agent runtime and the console's
 * 日志与标注 tab already read. Wires only when both modules are on the classpath
 * — kept in its own file so {@link ConditionalOnClass} can guard the entire type
 * without the agent-module symbols leaking into
 * {@link SpringAgentWebAutoConfiguration}.
 *
 * <p>Under {@link ConditionalOnMissingBean}: any application-published
 * {@link WorkflowConversationMemory} (e.g. Redis-backed) shadows the default.</p>
 */
@AutoConfiguration
@ConditionalOnClass({AgentMemory.class, WorkflowConversationMemory.class})
public class WorkflowMemoryBridgeAutoConfiguration {

    @Bean
    @ConditionalOnBean(AgentMemory.class)
    @ConditionalOnMissingBean(WorkflowConversationMemory.class)
    public WorkflowConversationMemory workflowConversationMemory(AgentMemory agentMemory) {
        return (conversationId, max) -> {
            if (conversationId == null || conversationId.isBlank() || max <= 0) {
                return List.of();
            }
            List<AgentMessage> loaded = agentMemory.load(conversationId, max);
            if (loaded == null || loaded.isEmpty()) return List.of();
            List<WorkflowConversationMemory.ConversationTurn> out = new ArrayList<>(loaded.size());
            for (AgentMessage m : loaded) {
                String role = m.role() == null
                        ? "user"
                        : m.role().name().toLowerCase(Locale.ROOT);
                out.add(new WorkflowConversationMemory.ConversationTurn(role, m.content()));
            }
            return out;
        };
    }
}
