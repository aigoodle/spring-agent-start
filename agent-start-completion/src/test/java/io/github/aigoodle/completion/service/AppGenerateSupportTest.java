package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AppEntity;
import io.github.aigoodle.agent.service.AppConversationService;
import io.github.aigoodle.common.context.UserContextHolder;
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
        ObjectProvider<AppConversationService> conversationServices = mock(ObjectProvider.class);
        when(conversationServices.getIfAvailable()).thenReturn(null);
        OpenAIChatRequest request = new OpenAIChatRequest();

        new ChatRequestInitializer(conversationServices, mock(Logger.class))
                .initialize(new AppEntity(), request);

        assertThat(request.getConversationId()).isNotBlank();
    }

    @Test
    void preservesClientProvidedConversationId() {
        ObjectProvider<AppConversationService> conversationServices = mock(ObjectProvider.class);
        when(conversationServices.getIfAvailable()).thenReturn(null);
        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setConversationId("existing-conversation");

        new ChatRequestInitializer(conversationServices, mock(Logger.class))
                .initialize(new AppEntity(), request);

        assertThat(request.getConversationId()).isEqualTo("existing-conversation");
    }

    @Test
    void bindsResolvedChatUserDuringExecutionAndClearsItAfterwards() {
        AppEntity application = new AppEntity();
        application.setId("app-1");
        application.setTenantId("demo-tenant");

        String resolved = AppGenerateService.callWithUser(application, "demo-user", () -> {
            assertThat(UserContextHolder.currentUserId()).isEqualTo("demo-user");
            assertThat(UserContextHolder.currentTenantId()).isEqualTo("demo-tenant");
            assertThat(UserContextHolder.currentAppId()).isEqualTo("app-1");
            return UserContextHolder.currentUserId();
        });

        assertThat(resolved).isEqualTo("demo-user");
        assertThat(UserContextHolder.get()).isNull();
    }

    private static AppEntity applicationWithMode(String mode) {
        AppEntity application = new AppEntity();
        application.setMode(mode);
        return application;
    }
}
