package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class AppGenerateSupportTest {

    @Test
    void recognizesBothSupportedFlowModes() {
        assertThat(AppChatRuntimeRouter.isFlowApplication(applicationWithMode("workflow"))).isTrue();
        assertThat(AppChatRuntimeRouter.isFlowApplication(applicationWithMode("chatflow"))).isTrue();
        assertThat(AppChatRuntimeRouter.isFlowApplication(applicationWithMode("agent"))).isFalse();
    }

    @Test
    void assignsConversationIdWhenConversationCatalogIsUnavailable() {
        ObjectProvider<ConversationService> conversationServices = mock(ObjectProvider.class);
        when(conversationServices.getIfAvailable()).thenReturn(null);
        OpenAIChatRequest request = new OpenAIChatRequest();

        new ChatRequestInitializer(conversationServices, mock(Logger.class))
                .initialize(new AgentEntity(), request);

        assertThat(request.getConversationId()).isNotBlank();
    }

    @Test
    void preservesClientProvidedConversationId() {
        ObjectProvider<ConversationService> conversationServices = mock(ObjectProvider.class);
        when(conversationServices.getIfAvailable()).thenReturn(null);
        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setConversationId("existing-conversation");

        new ChatRequestInitializer(conversationServices, mock(Logger.class))
                .initialize(new AgentEntity(), request);

        assertThat(request.getConversationId()).isEqualTo("existing-conversation");
    }

    private static AgentEntity applicationWithMode(String mode) {
        AgentEntity application = new AgentEntity();
        application.setMode(mode);
        return application;
    }
}
