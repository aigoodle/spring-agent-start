package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.PromptTemplateService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Classifies the input into one of several classes using an LLM, then branches on the
 * chosen class id (the outgoing handle = class id). Config:
 * {@code modelProvider} + {@code modelName}, {@code classes}
 * (list of {@code {id,name}}), {@code query} (template).
 */
public class QuestionClassifierNodeExecutor implements NodeExecutor {

    private final ModelService modelService;
    private final ClassifierPromptBuilder promptBuilder;

    public QuestionClassifierNodeExecutor(ModelService modelService) {
        this(modelService, null);
    }

    public QuestionClassifierNodeExecutor(ModelService modelService,
                                           PromptTemplateService promptTemplateService) {
        this.modelService = modelService;
        this.promptBuilder = new ClassifierPromptBuilder(promptTemplateService);
    }

    @Override
    public NodeType type() {
        return NodeType.QUESTION_CLASSIFIER;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        ChatClient chatClient;
        try {
            chatClient = NodeModelResolver.resolve(node, context, modelService);
        } catch (IllegalArgumentException exception) {
            return NodeResult.failure("Question classifier requires modelProvider + modelName");
        }
        ClassifierCategorySet categorySet = ClassifierCategorySet.from(node.getMapList("classes"));
        if (categorySet.isEmpty()) {
            return NodeResult.failure("Question classifier requires 'classes'");
        }
        String query = VariableResolver.render(
                node.getString("query", "{{#sys.query#}}"), context.getPool());

        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .system(promptBuilder.build(node, categorySet))
                .user(query);
        ChatOptions nodeOptions = NodeModelResolver.perNodeOptions(node);
        if (nodeOptions != null) {
            request = request.options(nodeOptions);
        }
        String modelResponse = request.call().content();
        ClassifierCategorySet.ClassifierCategory selectedCategory =
                categorySet.match(modelResponse);
        return NodeResult.empty()
                .output("classId", selectedCategory.id())
                .output("className", selectedCategory.name())
                .handle(selectedCategory.id());
    }
}
