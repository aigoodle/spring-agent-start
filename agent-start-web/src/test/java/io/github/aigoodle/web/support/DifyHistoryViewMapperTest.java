package io.github.aigoodle.web.support;

import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.web.dto.dify.DifyConversationVO;
import io.github.aigoodle.web.dto.dify.DifyMessageVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DifyHistoryViewMapperTest {

    private final DifyHistoryViewMapper mapper = new DifyHistoryViewMapper();

    @Test
    void combinesUserAndAssistantRowsIntoOneMessage() {
        AgentMessageEntity question = message("question-1", "USER", "How are you?", 1);
        AgentMessageEntity answer = message("answer-1", "ASSISTANT", "Great", 2);

        List<DifyMessageVO> messages = mapper.toMessages("conversation-1", List.of(question, answer));

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.getId()).isEqualTo("answer-1");
            assertThat(message.getConversationId()).isEqualTo("conversation-1");
            assertThat(message.getQuery()).isEqualTo("How are you?");
            assertThat(message.getAnswer()).isEqualTo("Great");
        });
    }

    @Test
    void preservesUnpairedQuestionsAndAnswers() {
        AgentMessageEntity firstQuestion = message("question-1", "USER", "First", 1);
        AgentMessageEntity secondQuestion = message("question-2", "USER", "Second", 2);
        AgentMessageEntity answer = message("answer-2", "ASSISTANT", "Reply", 3);
        AgentMessageEntity orphanAnswer = message("answer-3", "ASSISTANT", "Opening", 4);

        List<DifyMessageVO> messages = mapper.toMessages(
                "conversation-1", List.of(firstQuestion, secondQuestion, answer, orphanAnswer));

        assertThat(messages).extracting(DifyMessageVO::getId)
                .containsExactly("question-1", "answer-2", "answer-3");
        assertThat(messages.get(0).getAnswer()).isEmpty();
        assertThat(messages.get(2).getQuery()).isEmpty();
    }

    @Test
    void mapsConversationDefaultsAndTimestamps() {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("conversation-1");
        conversation.setName("Readable title");
        conversation.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));

        DifyConversationVO view = mapper.toConversation(conversation);

        assertThat(view.getStatus()).isEqualTo("normal");
        assertThat(view.getIntroduction()).isEmpty();
        assertThat(view.getUpdatedAt()).isEqualTo(view.getCreatedAt());
    }

    @Test
    void resolvesAppIdByDocumentedPrecedence() {
        assertThat(DifyAppIdResolver.resolve("query-app", "header-app", "Bearer token-app"))
                .isEqualTo("query-app");
        assertThat(DifyAppIdResolver.resolve(null, " header-app ", "Bearer token-app"))
                .isEqualTo("header-app");
        assertThat(DifyAppIdResolver.resolve(null, null, "Bearer token-app"))
                .isEqualTo("token-app");
        assertThatThrownBy(() -> DifyAppIdResolver.resolve(null, " ", null))
                .isInstanceOf(AgentException.class);
    }

    private static AgentMessageEntity message(String id, String role, String content, long sequence) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setSeq(sequence);
        message.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, Math.toIntExact(sequence)));
        return message;
    }
}
