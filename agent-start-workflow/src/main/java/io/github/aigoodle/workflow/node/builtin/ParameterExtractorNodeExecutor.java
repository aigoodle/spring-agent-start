package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Asks an LLM to pull structured fields out of a free-form input, mirroring Dify's
 * parameter-extractor node.
 * <p>
 * Config: {@code modelProvider} + {@code modelName} (required), {@code query}
 * (template), {@code parameters}
 * (list of {@code {name, type, description, required}}),
 * and an optional {@code systemPrompt} — either a plain string or an
 * object shaped like {@code {text: "..."}} (to match the frontend prompt
 * editor). When present, it is rendered as a variable template and prepended
 * to the fixed JSON-schema instruction. Each declared parameter is emitted as
 * an output; missing values default to {@code null}. Extraction failures
 * do not fail the node — they yield empty outputs so the graph can continue.
 * <p>
 * Honours per-node {@code model.completionParams} the same way the LLM node
 * does (via {@link NodeModelResolver#perNodeOptions}), so
 * {@code enable_thinking=false} / {@code thinkingMode=disabled} disables the
 * model's reasoning preamble here too — otherwise the extractor would burn
 * seconds on a reasoning trace it never uses before emitting the JSON.
 */
public class ParameterExtractorNodeExecutor implements NodeExecutor {

    private final ModelService modelService;

    public ParameterExtractorNodeExecutor(ModelService modelService) {
        this.modelService = modelService;
    }

    @Override
    public NodeType type() {
        return NodeType.PARAMETER_EXTRACTOR;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        ChatClient chatClient;
        try {
            chatClient = NodeModelResolver.resolve(node, context, modelService);
        } catch (IllegalArgumentException exception) {
            return NodeResult.failure("Parameter extractor requires modelProvider + modelName");
        }
        ExtractionParameterSet parameterSet = ExtractionParameterSet.from(
                node.getMapList("parameters"));
        if (parameterSet.isEmpty()) {
            return NodeResult.failure("Parameter extractor requires 'parameters'");
        }
        String query = VariableResolver.render(
                node.getString("query", "{{#sys.query#}}"), context.getPool());
        String systemPrompt = ParameterExtractionPromptBuilder.build(node, context, parameterSet);

        try {
            String modelResponse = invokeModel(chatClient, node, systemPrompt, query);
            return parameterSet.resultFrom(modelResponse);
        } catch (Exception extractionFailure) {
            // Extraction is non-fatal; every declared output remains addressable as null.
            return parameterSet.failedResult(extractionFailure);
        }
    }

    private static String invokeModel(ChatClient chatClient, NodeDef node,
                                      String systemPrompt, String query) {
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .system(systemPrompt)
                .user(query);
        ChatOptions nodeOptions = NodeModelResolver.perNodeOptions(node);
        if (nodeOptions != null) {
            request = request.options(nodeOptions);
        }
        return request.call().content();
    }
}
