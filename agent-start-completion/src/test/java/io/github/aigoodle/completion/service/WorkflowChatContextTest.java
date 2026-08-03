package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIMessage;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowChatContextTest {

    @Test
    void explicitWorkflowOverrideWins() {
        AgentEntity application = application("app-1", "published-workflow");
        OpenAIChatRequest request = request("Hello");
        request.setWorkflowId("requested-workflow");
        request.setDebug(true);

        WorkflowChatContext context = resolve(application, request);

        assertThat(context.workflowId()).isEqualTo("requested-workflow");
    }

    @Test
    void debugModeUsesDraftAndBuildsRuntimeInputs() {
        AgentEntity application = application("app-1", "published-workflow");
        OpenAIChatRequest request = request("Latest question");
        request.setDebug(true);
        request.setData(Map.of("language", "java"));

        WorkflowChatContext context = resolve(application, request);

        assertThat(context.workflowId()).isEqualTo("app-1");
        assertThat(context.conversationId()).isNotBlank();
        assertThat(request.getConversationId()).isEqualTo(context.conversationId());
        assertThat(context.inputs())
                .containsEntry("language", "java")
                .containsEntry("query", "Latest question")
                .containsEntry("conversation_id", context.conversationId());
    }

    @Test
    void answerExtractorHonoursPreferredOutputNames() {
        WorkflowRunResult result = new WorkflowRunResult();
        result.setOutputs(Map.of("result", "fallback", "answer", "preferred"));

        assertThat(WorkflowAnswerExtractor.extract(result)).isEqualTo("preferred");

        result.setOutputs(Map.of("custom", 42));
        assertThat(WorkflowAnswerExtractor.extract(result)).isEqualTo("42");
    }

    private static WorkflowChatContext resolve(AgentEntity application, OpenAIChatRequest request) {
        return WorkflowChatContext.resolve(
                application,
                request,
                LoggerFactory.getLogger(WorkflowChatContextTest.class));
    }

    private static AgentEntity application(String id, String workflowId) {
        AgentEntity application = new AgentEntity();
        application.setId(id);
        application.setWorkflowId(workflowId);
        return application;
    }

    private static OpenAIChatRequest request(String query) {
        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setMessages(List.of(OpenAIMessage.user(query)));
        return request;
    }
}
