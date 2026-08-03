package io.github.aigoodle.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.workflow.graph.WorkflowGraph;

import java.util.Objects;

/** Converts persisted designer JSON into the typed graph used by the runtime. */
final class WorkflowGraphCodec {

    WorkflowGraph read(JsonNode graphDefinition) {
        if (graphDefinition == null || graphDefinition.isNull()) {
            throw new AgentException("graph_required", "Workflow graph is required", null);
        }
        try {
            WorkflowGraph graph = JsonUtils.parse(graphDefinition.toString(), WorkflowGraph.class);
            graph.reindex();
            return graph;
        } catch (Exception exception) {
            throw new AgentException(
                    "graph_parse_error",
                    "Could not parse graph: " + exception.getMessage(),
                    exception);
        }
    }

    JsonNode write(WorkflowGraph graph) {
        return JsonUtils.mapper().valueToTree(Objects.requireNonNull(graph, "graph must not be null"));
    }

    JsonNode emptyGraph() {
        ObjectNode emptyGraph = JsonUtils.mapper().createObjectNode();
        emptyGraph.putArray("nodes");
        emptyGraph.putArray("edges");
        return emptyGraph;
    }
}
