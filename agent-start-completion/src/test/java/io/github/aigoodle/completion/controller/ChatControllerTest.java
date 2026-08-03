package io.github.aigoodle.completion.controller;

import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.service.AppGenerateService;
import io.github.aigoodle.completion.service.ConversationHistoryService;
import io.github.aigoodle.completion.support.AppAccessResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void rejectsConversationOwnedByAnotherApplication() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("conversation-1");
        conversation.setAppId("app-b");
        when(conversationService.require(conversation.getId())).thenReturn(conversation);

        ChatController controller = new ChatController(
                mock(AppGenerateService.class),
                new AppAccessResolver(emptyProvider()),
                new ConversationHistoryService(providerOf(conversationService), emptyProvider()));

        assertThatThrownBy(() -> controller.conversationMessages(
                "app-a", conversation.getId(), null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Conversation not found");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return mock(ObjectProvider.class);
    }
}
