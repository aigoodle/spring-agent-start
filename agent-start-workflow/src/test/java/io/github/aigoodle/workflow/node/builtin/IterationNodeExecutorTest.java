package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.engine.WorkflowEngine;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IterationNodeExecutorTest {

    @Test
    void failsAtTheExactItemWhenSubRunFails() {
        WorkflowEngine engine = mock(WorkflowEngine.class);
        when(engine.run(any(WorkflowGraph.class), anyMap(), isNull()))
                .thenReturn(success("first"))
                .thenReturn(failure("broken item"));
        IterationNodeExecutor executor = new IterationNodeExecutor(() -> engine);

        NodeResult result = executor.execute(iterationNode(false), contextWithItems());

        assertThat(result.isFailed()).isTrue();
        assertThat(result.getError()).isEqualTo("Iteration failed at index 1: broken item");
    }

    @Test
    void continueOnErrorKeepsOutputPositionsStable() {
        WorkflowEngine engine = mock(WorkflowEngine.class);
        when(engine.run(any(WorkflowGraph.class), anyMap(), isNull()))
                .thenReturn(failure("first failed"))
                .thenReturn(success("second"));
        IterationNodeExecutor executor = new IterationNodeExecutor(() -> engine);

        NodeResult result = executor.execute(iterationNode(true), contextWithItems());

        assertThat(result.isFailed()).isFalse();
        assertThat(iterationOutputs(result))
                .containsExactly(null, Map.of("value", "second"));
    }

    private static NodeDef iterationNode(boolean continueOnError) {
        WorkflowGraph subGraph = new WorkflowGraph();
        subGraph.addNode(NodeDef.of("start", NodeType.START));
        return NodeDef.of("iteration", NodeType.ITERATION)
                .with("inputList", "sys.items")
                .with("subGraph", subGraph)
                .with("continueOnError", continueOnError);
    }

    private static ExecutionContext contextWithItems() {
        return ExecutionContext.start(Map.of("items", List.of("first", "second")), null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> iterationOutputs(NodeResult result) {
        return (List<Object>) result.getOutputs().get("output");
    }

    private static WorkflowRunResult success(String value) {
        return WorkflowRunResult.forRun("run-success", new ArrayList<>())
                .succeed(Map.of("value", value));
    }

    private static WorkflowRunResult failure(String error) {
        return WorkflowRunResult.forRun("run-failure", new ArrayList<>())
                .fail(error, Map.of());
    }
}
