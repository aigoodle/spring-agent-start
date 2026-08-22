package io.github.aigoodle.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.workflow.graph.WorkflowGraph;

import java.util.Objects;

/** Converts persisted designer JSON into the typed graph used by the runtime. */
final class WorkflowGraphCodec {

    WorkflowGraph read(JsonNode graphDefinition) {
        if (graphDefinition == null || graphDefinition.isNull()) {
            throw new PlatformException("graph_required", "Workflow graph is required", null);
        }
        try {
            WorkflowGraph graph = JsonUtils.parse(graphDefinition.toString(), WorkflowGraph.class);
            graph.reindex();
            return graph;
        } catch (Exception exception) {
            throw new PlatformException(
                    "graph_parse_error",
                    "Could not parse graph: " + exception.getMessage(),
                    exception);
        }
    }

    JsonNode write(WorkflowGraph graph) {
        return JsonUtils.mapper().valueToTree(Objects.requireNonNull(graph, "graph must not be null"));
    }

    JsonNode emptyGraph() {
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(io.github.aigoodle.workflow.graph.NodeDef.of("start",
                io.github.aigoodle.workflow.graph.NodeType.START));
        graph.addNode(io.github.aigoodle.workflow.graph.NodeDef.of("end",
                io.github.aigoodle.workflow.graph.NodeType.END));
        graph.addEdge(io.github.aigoodle.workflow.graph.EdgeDef.of("start", "end"));
        return write(graph);
    }
}
