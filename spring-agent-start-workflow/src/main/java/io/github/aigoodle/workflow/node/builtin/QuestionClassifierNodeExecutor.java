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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies the input into one of several classes using an LLM, then branches on the
 * chosen class id (the outgoing handle = class id). Config:
 * {@code modelProvider} + {@code modelName}, {@code classes}
 * (list of {@code {id,name}}), {@code query} (template).
 */
public class QuestionClassifierNodeExecutor implements NodeExecutor {

    private final ModelService modelService;
    private final PromptTemplateService promptTemplateService;

    public QuestionClassifierNodeExecutor(ModelService modelService) {
        this(modelService, null);
    }

    public QuestionClassifierNodeExecutor(ModelService modelService,
                                           PromptTemplateService promptTemplateService) {
        this.modelService = modelService;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public NodeType type() {
        return NodeType.QUESTION_CLASSIFIER;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        ChatClient client;
        try {
            client = NodeModelResolver.resolve(node, ctx, modelService);
        } catch (IllegalArgumentException e) {
            return NodeResult.failure("Question classifier requires modelProvider + modelName");
        }
        List<Map<String, Object>> classes = node.getMapList("classes");
        if (classes.isEmpty()) {
            return NodeResult.failure("Question classifier requires 'classes'");
        }
        String query = VariableResolver.render(node.getString("query", "{{#sys.query#}}"), ctx.getPool());

        StringBuilder menu = new StringBuilder();
        for (Map<String, Object> c : classes) {
            menu.append("- ").append(c.get("id")).append(": ").append(c.get("name")).append("\n");
        }
        String system;
        String templateId = node.getString("systemPromptTemplateId");
        if (templateId != null && !templateId.isBlank() && promptTemplateService != null) {
            var tpl = promptTemplateService.get(templateId);
            if (tpl != null) {
                Map<String, Object> vars = new HashMap<>();
                vars.put("categories", menu.toString());
                system = promptTemplateService.render(tpl.getContent(), vars);
            } else {
                system = defaultClassifierPrompt(menu);
            }
        } else {
            system = defaultClassifierPrompt(menu);
        }

        String reply = client.prompt()
                .system(system).user(query).call().content();
        String answer = reply == null ? "" : reply.trim().toLowerCase();

        // Match the reply to a class id (or name) robustly.
        Map<String, Object> chosen = classes.get(classes.size() - 1); // default: last
        for (Map<String, Object> c : classes) {
            String id = String.valueOf(c.get("id")).toLowerCase();
            String name = String.valueOf(c.get("name")).toLowerCase();
            if (answer.contains(id) || (!name.isBlank() && answer.contains(name))) {
                chosen = c;
                break;
            }
        }
        String classId = String.valueOf(chosen.get("id"));
        return NodeResult.empty()
                .output("classId", classId)
                .output("className", chosen.get("name"))
                .handle(classId);
    }

    private static String defaultClassifierPrompt(CharSequence menu) {
        return "You are a precise text classifier. Choose exactly ONE category that best matches "
                + "the user input. Reply with ONLY the category id, nothing else.\nCategories:\n" + menu;
    }
}
