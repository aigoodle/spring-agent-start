package io.github.aigoodle.web.support;

import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.web.dto.dify.DifyConversationVO;
import io.github.aigoodle.web.dto.dify.DifyMessageVO;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Maps persistence rows to the public Dify conversation-history representation. */
@Component
@ConditionalOnClass(ConversationService.class)
public final class DifyHistoryViewMapper {

    public DifyConversationVO toConversation(ConversationEntity conversation) {
        DifyConversationVO view = new DifyConversationVO();
        view.setId(conversation.getId());
        view.setName(conversation.getName());
        view.setInputs(Collections.emptyMap());
        view.setStatus(conversation.getStatus() == null ? "normal" : conversation.getStatus());
        view.setIntroduction(conversation.getIntroduction() == null ? "" : conversation.getIntroduction());
        view.setCreatedAt(epochSecond(conversation.getCreatedAt()));
        LocalDateTime updatedAt = conversation.getUpdatedAt() == null
                ? conversation.getCreatedAt()
                : conversation.getUpdatedAt();
        view.setUpdatedAt(epochSecond(updatedAt));
        return view;
    }

    /** Combines adjacent user/assistant persistence rows into Dify's one-question-one-answer model. */
    public List<DifyMessageVO> toMessages(String conversationId, List<AgentMessageEntity> rows) {
        List<DifyMessageVO> messages = new ArrayList<>();
        AgentMessageEntity pendingQuestion = null;
        for (AgentMessageEntity row : rows) {
            if (hasRole(row, "USER")) {
                if (pendingQuestion != null) {
                    messages.add(toMessage(conversationId, pendingQuestion, null));
                }
                pendingQuestion = row;
            } else if (hasRole(row, "ASSISTANT")) {
                messages.add(toMessage(conversationId, pendingQuestion, row));
                pendingQuestion = null;
            }
        }
        if (pendingQuestion != null) {
            messages.add(toMessage(conversationId, pendingQuestion, null));
        }
        return messages;
    }

    private DifyMessageVO toMessage(String conversationId, AgentMessageEntity question,
                                    AgentMessageEntity answer) {
        AgentMessageEntity identityRow = answer != null ? answer : question;
        DifyMessageVO view = new DifyMessageVO();
        view.setId(identityRow.getId());
        view.setConversationId(conversationId);
        view.setInputs(Collections.emptyMap());
        view.setQuery(question == null ? "" : emptyIfNull(question.getContent()));
        view.setAnswer(answer == null ? "" : emptyIfNull(answer.getContent()));
        view.setMessageFiles(Collections.emptyList());
        view.setFeedback(null);
        view.setRetrieverResources(Collections.emptyList());
        view.setCreatedAt(epochSecond(identityRow.getCreatedAt()));
        return view;
    }

    private static boolean hasRole(AgentMessageEntity message, String expectedRole) {
        return expectedRole.equalsIgnoreCase(message.getRole());
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static long epochSecond(LocalDateTime time) {
        return time == null ? 0L : time.toEpochSecond(ZoneOffset.UTC);
    }
}
