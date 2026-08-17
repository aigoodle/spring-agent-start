package io.github.aigoodle.completion.controller;

import io.github.aigoodle.agent.entity.AppConversationEntity;
import io.github.aigoodle.agent.entity.AppApiTokenEntity;
import io.github.aigoodle.agent.service.AppApiTokenService;
import io.github.aigoodle.agent.service.AppConversationService;
import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.completion.service.AppGenerateService;
import io.github.aigoodle.completion.service.ConversationHistoryService;
import io.github.aigoodle.completion.support.ChatAccessPolicy;
import io.github.aigoodle.completion.support.AppAccessResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void openAIEndpointRejectsMissingApiKey() {
        ChatController controller = new ChatController(
                mock(AppGenerateService.class),
                new AppAccessResolver(emptyProvider()),
                mock(ChatAccessPolicy.class),
                mock(ConversationHistoryService.class));

        assertThatThrownBy(() -> controller.openAICompletions(null,
                new io.github.aigoodle.completion.dto.openai.OpenAIChatRequest()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void apiKeyResolvesApplicationWithoutClientSuppliedAppId() {
        AppApiTokenService tokenService = mock(AppApiTokenService.class);
        AppApiTokenEntity token = new AppApiTokenEntity();
        token.setId("token-1");
        token.setAppId("app-from-key");
        when(tokenService.findByToken("secret-key")).thenReturn(token);

        AppAccessResolver resolver = new AppAccessResolver(providerOf(tokenService));

        org.assertj.core.api.Assertions.assertThat(
                resolver.requireTokenApp("Bearer secret-key"))
                .isEqualTo("app-from-key");
    }

    @Test
    void rejectsConversationOwnedByAnotherApplication() {
        AppConversationService conversationService = mock(AppConversationService.class);
        AppConversationEntity conversation = new AppConversationEntity();
        conversation.setId("conversation-1");
        conversation.setAppId("app-b");
        when(conversationService.require(conversation.getId())).thenReturn(conversation);

        ChatController controller = new ChatController(
                mock(AppGenerateService.class),
                new AppAccessResolver(emptyProvider()),
                mock(ChatAccessPolicy.class),
                new ConversationHistoryService(providerOf(conversationService), emptyProvider()));

        assertThatThrownBy(() -> controller.conversationMessages(
                "app-a", conversation.getId(), null))
                .isInstanceOf(PlatformException.class)
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
