package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * A tool-calling agent node. Backed by Spring AI's tool-calling loop: the model may
 * iteratively call any of the registered {@link ToolCallback}s until it produces a
 * final answer. Config: {@code modelProvider} + {@code modelName} (required),
 * {@code systemPrompt} (template), {@code query} (template), {@code tools}
 * (optional list of tool names to allow).
 */
public class AgentNodeExecutor implements NodeExecutor {

    private final ModelService modelService;
    private final List<ToolCallback> toolCallbacks;

    public AgentNodeExecutor(ModelService modelService, List<ToolCallback> toolCallbacks) {
        this.modelService = modelService;
        this.toolCallbacks = toolCallbacks == null ? List.of() : toolCallbacks;
    }

    @Override
    public NodeType type() {
        return NodeType.AGENT;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        ChatClient client;
        try {
            client = NodeModelResolver.resolve(node, ctx, modelService);
        } catch (IllegalArgumentException e) {
            return NodeResult.failure("Agent node requires modelProvider + modelName");
        }
        String system = VariableResolver.render(
                node.getString("systemPrompt", "You are a helpful assistant. Use tools when helpful."),
                ctx.getPool());
        String query = VariableResolver.render(node.getString("query", "{{#sys.query#}}"), ctx.getPool());

        List<ToolCallback> selected = selectTools(node);
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(system).user(query);
        if (!selected.isEmpty()) {
            spec = spec.toolCallbacks(selected.toArray(new ToolCallback[0]));
        }
        String text = spec.call().content();
        return NodeResult.of("text", text);
    }

    private List<ToolCallback> selectTools(NodeDef node) {
        Object names = node.get("tools");
        if (!(names instanceof List<?> list) || list.isEmpty()) {
            return toolCallbacks;
        }
        List<String> allowed = list.stream().map(String::valueOf).toList();
        List<ToolCallback> selected = new ArrayList<>();
        for (ToolCallback cb : toolCallbacks) {
            String name = toolName(cb);
            if (name != null && allowed.contains(name)) {
                selected.add(cb);
            }
        }
        return selected;
    }

    private static String toolName(ToolCallback cb) {
        try {
            return cb.getToolDefinition().name();
        } catch (Exception e) {
            return null;
        }
    }
}
