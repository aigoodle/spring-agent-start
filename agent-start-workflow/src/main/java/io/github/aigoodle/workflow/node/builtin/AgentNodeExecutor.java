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
    private final AgentToolSelector toolSelector;

    public AgentNodeExecutor(ModelService modelService, List<ToolCallback> toolCallbacks) {
        this.modelService = modelService;
        this.toolSelector = new AgentToolSelector(toolCallbacks);
    }

    @Override
    public NodeType type() {
        return NodeType.AGENT;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        ChatClient chatClient;
        try {
            chatClient = NodeModelResolver.resolve(node, context, modelService);
        } catch (IllegalArgumentException exception) {
            return NodeResult.failure("Agent node requires modelProvider + modelName");
        }

        String systemPrompt = VariableResolver.render(
                node.getString("systemPrompt", "You are a helpful assistant. Use tools when helpful."),
                context.getPool());
        String userQuery = VariableResolver.render(
                node.getString("query", "{{#sys.query#}}"), context.getPool());

        List<ToolCallback> selectedTools = toolSelector.selectFor(node);
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .system(systemPrompt)
                .user(userQuery);
        if (!selectedTools.isEmpty()) {
            request = request.toolCallbacks(selectedTools.toArray(ToolCallback[]::new));
        }
        return NodeResult.of("text", request.call().content());
    }

}
