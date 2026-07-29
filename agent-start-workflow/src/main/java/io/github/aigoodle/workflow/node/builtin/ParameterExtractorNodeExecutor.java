package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.Map;

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
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        ChatClient client;
        try {
            client = NodeModelResolver.resolve(node, ctx, modelService);
        } catch (IllegalArgumentException e) {
            return NodeResult.failure("Parameter extractor requires modelProvider + modelName");
        }
        List<Map<String, Object>> params = node.getMapList("parameters");
        if (params.isEmpty()) {
            return NodeResult.failure("Parameter extractor requires 'parameters'");
        }
        String query = VariableResolver.render(node.getString("query", "{{#sys.query#}}"), ctx.getPool());

        StringBuilder schema = new StringBuilder("{\n");
        for (int i = 0; i < params.size(); i++) {
            Map<String, Object> p = params.get(i);
            schema.append("  \"").append(p.get("name")).append("\": ")
                    .append("\"").append(p.getOrDefault("type", "string")).append("\"");
            Object desc = p.get("description");
            if (desc != null) {
                schema.append("  // ").append(desc);
            }
            if (i < params.size() - 1) {
                schema.append(',');
            }
            schema.append('\n');
        }
        schema.append('}');

        String schemaInstruction = "Extract the fields described by the JSON schema. Reply with ONLY the JSON object, "
                + "no code fences, no prose. Use null when a field is missing.\nSchema:\n" + schema;
        String userSystem = extractSystemPrompt(node);
        if (!userSystem.isBlank()) {
            userSystem = VariableResolver.render(userSystem, ctx.getPool());
        }
        String system = userSystem.isBlank()
                ? schemaInstruction
                : userSystem + "\n\n" + schemaInstruction;

        NodeResult result = NodeResult.empty();
        try {
            ChatClient.ChatClientRequestSpec spec = client.prompt()
                    .system(system).user(query);
            ChatOptions perNode = NodeModelResolver.perNodeOptions(node);
            if (perNode != null) {
                spec = spec.options(perNode);
            }
            String reply = spec.call().content();
            Map<String, Object> extracted = JsonUtils.parseMap(stripFences(reply));
            for (Map<String, Object> p : params) {
                String name = String.valueOf(p.get("name"));
                result.output(name, extracted == null ? null : extracted.get(name));
            }
            result.output("result", reply);
        } catch (Exception e) {
            // Non-fatal: emit nulls so the workflow can continue with defaults.
            for (Map<String, Object> p : params) {
                result.output(String.valueOf(p.get("name")), null);
            }
            result.output("error", e.getMessage());
        }
        return result;
    }

    /**
     * The frontend prompt editor persists {@code systemPrompt} as
     * {@code {id, role, text}}, but hand-authored configs may pass a bare
     * string. Accept both and return an empty string when unset.
     */
    private static String extractSystemPrompt(NodeDef node) {
        Object raw = node.get("systemPrompt");
        if (raw == null) {
            return "";
        }
        if (raw instanceof Map<?, ?> m) {
            Object t = m.get("text");
            return t == null ? "" : String.valueOf(t);
        }
        return String.valueOf(raw);
    }

    private static String stripFences(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                t = t.substring(firstNl + 1, lastFence).trim();
            }
        }
        return t;
    }
}
