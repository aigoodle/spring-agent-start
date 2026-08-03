package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;
import io.github.aigoodle.agent.mapper.ConversationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    @Test
    void returnsExistingConversationWithoutOverwritingMetadata() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationEntity existingConversation = new ConversationEntity();
        existingConversation.setId("conversation-1");
        existingConversation.setName("Existing title");
        when(conversationMapper.selectById(existingConversation.getId()))
                .thenReturn(existingConversation);
        ConversationService conversationService = new ConversationService(
                conversationMapper, mock(AgentMessageMapper.class));

        ConversationEntity resolvedConversation = conversationService.ensure(
                existingConversation.getId(), "app-1", "tenant-1", "New title");

        assertThat(resolvedConversation).isSameAs(existingConversation);
        assertThat(resolvedConversation.getName()).isEqualTo("Existing title");
        verify(conversationMapper, never()).insert(any(ConversationEntity.class));
    }

    @Test
    void createsConversationWithNormalizedTenantAndBoundedTitle() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationService conversationService = new ConversationService(
                conversationMapper, mock(AgentMessageMapper.class));
        String firstMessage = "A".repeat(100);

        ConversationEntity conversation = conversationService.ensure(
                "conversation-1", "app-1", "  ", firstMessage);

        ArgumentCaptor<ConversationEntity> insertedConversation =
                ArgumentCaptor.forClass(ConversationEntity.class);
        verify(conversationMapper).insert(insertedConversation.capture());
        assertThat(conversation).isSameAs(insertedConversation.getValue());
        assertThat(conversation.getTenantId()).isEqualTo("default");
        assertThat(conversation.getName()).hasSize(60).endsWith("…");
        assertThat(conversation.getPinned()).isFalse();
        assertThat(conversation.getStatus()).isEqualTo("normal");
    }
}
