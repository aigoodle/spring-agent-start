package io.github.aigoodle.web.support;

import io.github.aigoodle.workflow.graph.NodeType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Builds the compact node metadata consumed by the visual workflow palette. */
public final class WorkflowNodeCatalog {

    private WorkflowNodeCatalog() {
    }

    public static List<Map<String, Object>> entries() {
        return Arrays.stream(NodeType.values())
                .map(nodeType -> Map.<String, Object>of(
                        "name", nodeType.name(),
                        "category", categoryOf(nodeType)))
                .toList();
    }

    private static String categoryOf(NodeType nodeType) {
        return switch (nodeType) {
            case START, END, ANSWER, IF_ELSE, ITERATION -> "flow";
            case TEMPLATE_TRANSFORM, VARIABLE_ASSIGNER, VARIABLE_AGGREGATOR, LIST_OPERATOR, CODE -> "data";
            case HTTP_REQUEST, SERVICE_API, DOCUMENT_EXTRACTOR, KNOWLEDGE_RETRIEVAL, TOOL -> "io";
            case LLM, AGENT, QUESTION_CLASSIFIER, PARAMETER_EXTRACTOR -> "llm";
        };
    }
}
