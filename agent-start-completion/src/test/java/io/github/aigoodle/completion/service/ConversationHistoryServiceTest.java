package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationHistoryServiceTest {

    @Test
    void buildsAReadableConversationSummaryFromTheFirstUserMessage() {
        ConversationService conversations = mock(ConversationService.class);
        AgentMemory memory = mock(AgentMemory.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("conversation-1");
        conversation.setName("New conversation");
        conversation.setFromEndUserId("user-1");
        when(conversations.listByApp("app-1")).thenReturn(List.of(conversation));
        when(memory.load("conversation-1", 20)).thenReturn(List.of(
                AgentMessage.assistant("Hello"),
                AgentMessage.user("Explain the quarterly report")));
        ConversationHistoryService history = new ConversationHistoryService(
                providerOf(conversations), providerOf(memory));

        List<Map<String, Object>> views = history.conversations("app-1", 10);

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view).containsEntry("conversationId", "conversation-1");
            assertThat(view).containsEntry("userId", "user-1");
            assertThat(view).containsEntry(
                    "firstMessage", "Explain the quarterly report");
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
