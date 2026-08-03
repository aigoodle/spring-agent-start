package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.util.Map;

/** Builds the system prompt while accepting both designer and hand-authored shapes. */
final class ParameterExtractionPromptBuilder {

    private ParameterExtractionPromptBuilder() {
    }

    static String build(
            NodeDef node, ExecutionContext context, ExtractionParameterSet parameterSet) {
        String schemaInstruction = parameterSet.schemaInstruction();
        String customInstruction = configuredSystemPrompt(node);
        if (customInstruction.isBlank()) {
            return schemaInstruction;
        }
        return VariableResolver.render(customInstruction, context.getPool())
                + "\n\n" + schemaInstruction;
    }

    private static String configuredSystemPrompt(NodeDef node) {
        Object configuredPrompt = node.get("systemPrompt");
        if (configuredPrompt instanceof Map<?, ?> promptData) {
            Object text = promptData.get("text");
            return text == null ? "" : String.valueOf(text);
        }
        return configuredPrompt == null ? "" : String.valueOf(configuredPrompt);
    }
}
