package io.github.aigoodle.agent.memory;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorAgentMemoryTest {

    @Test
    void returnsNoSemanticMemoryWithoutConversationIdentity() {
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        VectorAgentMemory memory = new VectorAgentMemory(
                knowledgeService, mock(DatasetService.class), "embedding-1", "memory", null);

        List<AgentMessage> recalledMessages = memory.recall(null, "remember this", 5);

        assertThat(recalledMessages).isEmpty();
        verify(knowledgeService, never()).retrieve(
                anyString(), any(RetrievalRequest.class));
    }

    @Test
    void blankQueryUsesChronologicalFallbackWithoutCreatingDataset() {
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        DatasetService datasetService = mock(DatasetService.class);
        AgentMemory fallbackMemory = mock(AgentMemory.class);
        List<AgentMessage> recentMessages = List.of(AgentMessage.user("hello"));
        when(fallbackMemory.load("conversation-1", 3)).thenReturn(recentMessages);
        VectorAgentMemory memory = new VectorAgentMemory(
                knowledgeService, datasetService, "embedding-1", " ", fallbackMemory);

        List<AgentMessage> recalledMessages = memory.recall("conversation-1", " ", 3);

        assertThat(recalledMessages).isSameAs(recentMessages);
        verify(datasetService, never()).create(any());
        verify(knowledgeService, never()).retrieve(
                anyString(), any(RetrievalRequest.class));
    }
}
