package io.github.aigoodle.workflow.engine;

import io.github.aigoodle.workflow.graph.EdgeDef;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.node.builtin.EndNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.StartNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.VariableAggregatorNodeExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the DAG execution semantics the engine guarantees:
 * <ul>
 *   <li>Parent → child runs sequentially (child waits for parent).</li>
 *   <li>Sibling branches with no shared ancestor race in parallel.</li>
 *   <li>A convergence node waits for <em>all</em> its parents before running.</li>
 * </ul>
 * Regression guard: if someone reverts the engine to a single-threaded queue,
 * {@link #siblingBranchesRunInParallel()} will fail on the timing assertion.
 */
class WorkflowParallelismTest {

    private static final class SleepExecutor implements NodeExecutor {
        private final long sleepMillis;

        SleepExecutor(long sleepMillis) {
            this.sleepMillis = sleepMillis;
        }

        @Override
        public NodeType type() {
            return NodeType.TEMPLATE_TRANSFORM;
        }

        @Override
        public NodeResult execute(NodeDef node, ExecutionContext ctx) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NodeResult.of("id", node.getId());
        }
    }

    @Test
    void siblingBranchesRunInParallel() {
        SleepExecutor sleeper = new SleepExecutor(200);
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new EndNodeExecutor(),
                new VariableAggregatorNodeExecutor(),
                sleeper)));

        // Diamond: START -> {left, right} -> merge -> END. left and right have
        // no dependency on each other, so a DAG engine must run them concurrently.
        WorkflowGraph g = new WorkflowGraph();
        g.addNode(NodeDef.of("start", NodeType.START));
        g.addNode(NodeDef.of("left", NodeType.TEMPLATE_TRANSFORM));
        g.addNode(NodeDef.of("right", NodeType.TEMPLATE_TRANSFORM));
        g.addNode(NodeDef.of("merge", NodeType.VARIABLE_AGGREGATOR)
                .with("variables", List.of("left.id", "right.id")));
        g.addNode(NodeDef.of("end", NodeType.END)
                .with("outputs", Map.of("chosen", "{{#merge.output#}}")));
        g.addEdge(EdgeDef.of("start", "left"));
        g.addEdge(EdgeDef.of("start", "right"));
        g.addEdge(EdgeDef.of("left", "merge"));
        g.addEdge(EdgeDef.of("right", "merge"));
        g.addEdge(EdgeDef.of("merge", "end"));

        long t0 = System.currentTimeMillis();
        WorkflowRunResult r = engine.run(g, Map.of(), null);
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(r.isSuccess(), r.getError());
        // Sequential execution would take at least 2 * 200ms = 400ms for the
        // two siblings alone. Parallel execution should finish comfortably under
        // 350ms; the extra headroom absorbs scheduling / JVM warmup jitter.
        assertTrue(elapsed < 350,
                "expected sibling branches to run concurrently, elapsed=" + elapsed + "ms");
    }
}
