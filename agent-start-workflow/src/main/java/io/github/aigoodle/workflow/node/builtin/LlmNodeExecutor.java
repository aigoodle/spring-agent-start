package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.PromptTemplateService;
import io.github.aigoodle.workflow.chat.ChatFluxHandle;
import io.github.aigoodle.workflow.chat.ChatStreamSink;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.memory.WorkflowConversationMemory;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Executes an LLM workflow node in structured, streaming or blocking mode.
 * Conversation construction—including saved templates and memory—is delegated
 * to {@link LlmConversationBuilder}, leaving this class focused on model execution.
 */
public class LlmNodeExecutor implements NodeExecutor {

    private static final String MISSING_MODEL_MESSAGE =
            "LLM node requires modelProvider + modelName";

    private final ModelService modelService;
    private final LlmConversationBuilder conversationBuilder;

    public LlmNodeExecutor(ModelService modelService) {
        this(modelService, null, null);
    }

    public LlmNodeExecutor(ModelService modelService,
                           PromptTemplateService promptTemplateService) {
        this(modelService, promptTemplateService, null);
    }

    public LlmNodeExecutor(ModelService modelService,
                           PromptTemplateService promptTemplateService,
                           WorkflowConversationMemory conversationMemory) {
        this.modelService = modelService;
        this.conversationBuilder = new LlmConversationBuilder(
                promptTemplateService, conversationMemory);
    }

    @Override
    public NodeType type() {
        return NodeType.LLM;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        List<Message> messages = conversationBuilder.build(node, context);
        boolean structuredOutput = requiresStructuredOutput(node);
        if (shouldStream(context.getChatSink(), structuredOutput)) {
            return executeStreaming(node, context, messages);
        }
        return executeBlocking(node, context, messages, structuredOutput);
    }

    private NodeResult executeStreaming(NodeDef node, ExecutionContext context,
                                        List<Message> messages) {
        ChatModel chatModel;
        try {
            chatModel = NodeModelResolver.resolveModel(node, context, modelService);
        } catch (IllegalArgumentException exception) {
            return NodeResult.failure(MISSING_MODEL_MESSAGE);
        }

        Prompt prompt = new Prompt(messages, resolveChatOptions(node, chatModel));
        Flux<String> tokens = chatModel.stream(prompt)
                .mapNotNull(LlmNodeExecutor::extractDelta)
                .filter(delta -> !delta.isEmpty());
        return NodeResult.of("text", new ChatFluxHandle(tokens));
    }

    private NodeResult executeBlocking(NodeDef node, ExecutionContext context,
                                       List<Message> messages, boolean structuredOutput) {
        ChatClient chatClient;
        try {
            chatClient = NodeModelResolver.resolve(node, context, modelService);
        } catch (IllegalArgumentException exception) {
            return NodeResult.failure(MISSING_MODEL_MESSAGE);
        }

        ChatClient.ChatClientRequestSpec request = chatClient.prompt().messages(messages);
        ChatOptions nodeOptions = NodeModelResolver.perNodeOptions(node);
        if (nodeOptions != null) {
            request = request.options(nodeOptions);
        }
        if (structuredOutput) {
            return executeStructured(request);
        }
        return NodeResult.of("text", request.call().content());
    }

    private static boolean shouldStream(ChatStreamSink streamSink, boolean structuredOutput) {
        return !structuredOutput && streamSink != null && streamSink.isStreaming();
    }

    private static ChatOptions resolveChatOptions(NodeDef node, ChatModel chatModel) {
        ChatOptions nodeOptions = NodeModelResolver.perNodeOptions(node);
        return nodeOptions != null ? nodeOptions : chatModel.getDefaultOptions();
    }

    private static String extractDelta(ChatResponse response) {
        if (response == null) {
            return null;
        }
        Generation generation = response.getResult();
        if (generation == null) {
            return null;
        }
        AssistantMessage message = generation.getOutput();
        if (message == null) {
            return null;
        }
        String text = message.getText();
        return text == null ? "" : text;
    }

    private static boolean requiresStructuredOutput(NodeDef node) {
        Object configuredFlag = node.get("structOutputEnabled");
        if (configuredFlag != null) {
            return configuredFlag instanceof Boolean booleanFlag
                    ? booleanFlag : Boolean.parseBoolean(String.valueOf(configuredFlag));
        }
        return node.get("structOutput") != null;
    }

    private static NodeResult executeStructured(ChatClient.ChatClientRequestSpec request) {
        return LlmStructuredOutputMapper.map(request.call().content());
    }
}
