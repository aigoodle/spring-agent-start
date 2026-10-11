package io.github.aigoodle.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.workflow.graph.EdgeDef;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowGraphCodecTest {

    private final WorkflowGraphCodec graphCodec = new WorkflowGraphCodec();

    @Test
    void roundTripsTypedWorkflowGraph() {
        WorkflowGraph graph = new WorkflowGraph();
        graph.setNodes(List.of(
                NodeDef.of("start", NodeType.START),
                NodeDef.of("end", NodeType.END)));
        graph.setEdges(List.of(EdgeDef.of("start", "end")));

        WorkflowGraph decoded = graphCodec.read(graphCodec.write(graph));

        assertThat(decoded.getNodes()).extracting(NodeDef::getId).containsExactly("start", "end");
        assertThat(decoded.getEdges()).hasSize(1);
        assertThat(decoded.startNode().getId()).isEqualTo("start");
    }

    @Test
    void createsMinimalPublishableDesignerGraph() {
        JsonNode emptyGraph = graphCodec.emptyGraph();

        assertThat(emptyGraph.path("nodes").isArray()).isTrue();
        assertThat(emptyGraph.path("nodes")).hasSize(2);
        assertThat(emptyGraph.path("edges").isArray()).isTrue();
        assertThat(emptyGraph.path("edges")).hasSize(1);
    }

    @Test
    void removesIdenticalLegacyEdgesButPreservesDifferentBranchHandles() {
        JsonNode source = io.github.aigoodle.common.util.JsonUtils.mapper().valueToTree(java.util.Map.of(
                "nodes", List.of(
                        java.util.Map.of("id", "start", "type", "START"),
                        java.util.Map.of("id", "branch", "type", "IF_ELSE"),
                        java.util.Map.of("id", "llm", "type", "LLM")),
                "edges", List.of(
                        java.util.Map.of("source", "branch", "target", "llm", "sourceHandle", "true"),
                        java.util.Map.of("source", "branch", "target", "llm", "sourceHandle", "true"),
                        java.util.Map.of("source", "branch", "target", "llm", "sourceHandle", "false"))));

        WorkflowGraph decoded = graphCodec.read(source);

        assertThat(decoded.getEdges()).hasSize(2);
        assertThat(decoded.getEdges()).extracting(EdgeDef::getSourceHandle)
                .containsExactly("true", "false");
    }

    @Test
    void rejectsMissingGraphWithDomainError() {
        assertThatThrownBy(() -> graphCodec.read(null))
                .isInstanceOf(PlatformException.class)
                .hasMessage("Workflow graph is required");
    }
}
