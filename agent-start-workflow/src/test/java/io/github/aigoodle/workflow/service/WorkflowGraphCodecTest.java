package io.github.aigoodle.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.common.exception.AgentException;
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
    void createsStructurallyValidEmptyDesignerGraph() {
        JsonNode emptyGraph = graphCodec.emptyGraph();

        assertThat(emptyGraph.path("nodes").isArray()).isTrue();
        assertThat(emptyGraph.path("nodes")).isEmpty();
        assertThat(emptyGraph.path("edges").isArray()).isTrue();
        assertThat(emptyGraph.path("edges")).isEmpty();
    }

    @Test
    void rejectsMissingGraphWithDomainError() {
        assertThatThrownBy(() -> graphCodec.read(null))
                .isInstanceOf(AgentException.class)
                .hasMessage("Workflow graph is required");
    }
}
