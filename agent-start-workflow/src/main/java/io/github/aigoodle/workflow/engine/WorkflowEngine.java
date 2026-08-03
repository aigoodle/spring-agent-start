package io.github.aigoodle.workflow.engine;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.workflow.graph.EdgeDef;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.node.StepRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Executes a {@link WorkflowGraph} as a DAG: independent branches run in parallel.
 * <p>
 * Every node's execution is wrapped in a {@link CompletableFuture} whose upstream
 * dependencies are its incoming edges' source nodes. A node runs only after all of
 * its structural parents have completed (executed or skipped), and only if at least
 * one incoming edge "fired" — i.e. the parent executed and produced a matching
 * {@code sourceHandle}. Untaken branches propagate as a "skipped" outcome so
 * downstream nodes correctly cascade to skipped without running.
 * <p>
 * Concurrency:
 * <ul>
 *   <li>Nodes run on a per-run virtual-thread executor — ideal for the I/O-heavy
 *       node types (HTTP / LLM / tools / retrieval).</li>
 *   <li>The variable pool is a {@link ConcurrentHashMap}; parent writes happen-before
 *       child reads via {@link CompletableFuture} completion.</li>
 *   <li>Step recording + the optional {@code stepListener} are serialised under a
 *       shared lock so observers see a totally-ordered stream, and the returned
 *       {@code steps} list stays consistent for callers that persist it.</li>
 * </ul>
 * A step ceiling still guards against pathologically large graphs; explicit iteration
 * is handled by the ITERATION node, not by re-entrant graph edges.
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final int MAX_STEPS = 1000;

    private final NodeExecutorRegistry executorRegistry;

    public WorkflowEngine(NodeExecutorRegistry executorRegistry) {
        this.executorRegistry = executorRegistry;
    }

    public WorkflowRunResult run(WorkflowGraph graph, Map<String, Object> inputs, String conversationId) {
        return run(graph, inputs, conversationId, null, null);
    }

    public WorkflowRunResult run(WorkflowGraph graph, Map<String, Object> inputs, String conversationId,
                                  Consumer<StepRecord> stepListener) {
        return run(graph, inputs, conversationId, stepListener, null);
    }

    /**
     * Full-fidelity entry point: threads an optional {@link
     * io.github.aigoodle.workflow.chat.ChatStreamSink} into the context so
     * downstream LLM / ANSWER nodes can push token-level deltas back to the
     * caller (SSE). Existing blocking callers still work by passing null.
     */
    public WorkflowRunResult run(WorkflowGraph graph, Map<String, Object> inputs, String conversationId,
                                  Consumer<StepRecord> stepListener,
                                  io.github.aigoodle.workflow.chat.ChatStreamSink chatSink) {
        graph.reindex();
        ExecutionContext context = ExecutionContext.start(inputs, conversationId, chatSink);
        WorkflowRunResult result = WorkflowRunResult.forRun(context.getRunId(), context.getSteps());
        RunState run = new RunState(context, stepListener);

        NodeDef startNode = graph.startNode();
        Map<String, List<EdgeDef>> incomingByTarget = indexIncoming(graph);

        Map<String, CompletableFuture<Void>> nodeFutures = new HashMap<>();
        for (NodeDef node : graph.getNodes()) {
            nodeFutures.put(node.getId(), new CompletableFuture<>());
        }

        // A per-run virtual-thread executor: nodes are typically I/O-bound (HTTP,
        // LLM, retrieval), so per-task virtual threads give ideal parallelism
        // without needing a bounded pool tuned for blocking.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (NodeDef node : graph.getNodes()) {
                scheduleNode(node, startNode, incomingByTarget.getOrDefault(node.getId(), List.of()),
                        nodeFutures, run, executor);
            }

            try {
                CompletableFuture.allOf(nodeFutures.values().toArray(new CompletableFuture[0])).join();
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.error("Workflow run {} failed: {}", context.getRunId(), cause.getMessage(), cause);
                return result.fail(cause.getMessage(), run.endOutputs);
            } catch (Exception ex) {
                log.error("Workflow run {} failed: {}", context.getRunId(), ex.getMessage(), ex);
                return result.fail(ex.getMessage(), run.endOutputs);
            }
        }

        String failureMessage = run.failure.get();
        if (failureMessage != null) {
            return result.fail(failureMessage, run.endOutputs);
        }
        return result.succeed(run.endOutputs);
    }

    private void scheduleNode(NodeDef node, NodeDef startNode, List<EdgeDef> incoming,
                              Map<String, CompletableFuture<Void>> nodeFutures,
                              RunState run,
                              Executor executor) {
        boolean isStart = node.getId().equals(startNode.getId());
        CompletableFuture<Void> nodeFuture = nodeFutures.get(node.getId());

        Runnable nodeTask = () -> executeNode(node, isStart, incoming, run, nodeFuture);

        if (isStart) {
            executor.execute(nodeTask);
            return;
        }
        if (incoming.isEmpty()) {
            // Orphan node: never reachable from START, so skip and let the run finish.
            run.outcomes.put(node.getId(), NodeOutcome.skipped());
            nodeFuture.complete(null);
            return;
        }
        CompletableFuture<?>[] parents = incoming.stream()
                .map(edge -> nodeFutures.get(edge.getSource()))
                .filter(Objects::nonNull)
                .distinct()
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(parents).thenRunAsync(nodeTask, executor);
    }

    private void executeNode(NodeDef node, boolean isStart, List<EdgeDef> incoming,
                        RunState run,
                        CompletableFuture<Void> nodeFuture) {
        try {
            boolean fired = isStart || anyIncomingFired(incoming, run.outcomes);
            if (!fired || run.failure.get() != null) {
                run.outcomes.put(node.getId(), NodeOutcome.skipped());
                return;
            }
            if (run.stepCount.incrementAndGet() > MAX_STEPS) {
                run.failure.compareAndSet(null,
                        new AgentException("max_steps",
                                "Workflow exceeded " + MAX_STEPS + " steps", null).getMessage());
                run.outcomes.put(node.getId(), NodeOutcome.skipped());
                return;
            }

            long start = System.nanoTime();
            NodeResult nodeResult;
            try {
                NodeExecutor nodeExecutor = executorRegistry.get(node.getType());
                nodeResult = nodeExecutor.execute(node, run.context);
            } catch (Exception exception) {
                log.error("Node {} ({}) failed: {}", node.getId(), node.getType(), exception.getMessage(), exception);
                nodeResult = NodeResult.failure(exception.getMessage());
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            run.record(node, nodeResult, elapsedMillis);

            if (nodeResult.isFailed()) {
                run.failure.compareAndSet(null, "Node " + node.getId() + " failed: " + nodeResult.getError());
                run.outcomes.put(node.getId(), NodeOutcome.executed(nodeResult.getHandle()));
                return;
            }
            if (node.getType() == NodeType.END && nodeResult.getOutputs() != null) {
                run.endOutputs.putAll(nodeResult.getOutputs());
            }
            run.outcomes.put(node.getId(), NodeOutcome.executed(nodeResult.getHandle()));
        } finally {
            nodeFuture.complete(null);
        }
    }

    private static boolean anyIncomingFired(List<EdgeDef> incoming, Map<String, NodeOutcome> outcomes) {
        for (EdgeDef edge : incoming) {
            NodeOutcome parent = outcomes.get(edge.getSource());
            if (parent != null && parent.executed() && matches(edge, parent.handle())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<EdgeDef>> indexIncoming(WorkflowGraph graph) {
        Map<String, List<EdgeDef>> incoming = new HashMap<>();
        for (NodeDef node : graph.getNodes()) {
            incoming.put(node.getId(), new ArrayList<>());
        }
        for (EdgeDef edge : graph.getEdges()) {
            List<EdgeDef> incomingEdges = incoming.get(edge.getTarget());
            if (incomingEdges != null) {
                incomingEdges.add(edge);
            }
        }
        return incoming;
    }

    private static boolean matches(EdgeDef edge, String handle) {
        if (edge.getSourceHandle() == null) {
            return handle == null;
        }
        return edge.getSourceHandle().equals(handle);
    }

    private static void notifyStepListener(Consumer<StepRecord> listener, StepRecord step) {
        if (listener == null) {
            return;
        }
        try {
            listener.accept(step);
        } catch (Exception exception) {
            log.warn("Workflow step listener threw: {}", exception.getMessage());
        }
    }

    /** Whether a node executed (with the chosen handle) or was skipped due to a dead branch. */
    private record NodeOutcome(boolean executed, String handle) {
        static NodeOutcome skipped() {
            return new NodeOutcome(false, null);
        }

        static NodeOutcome executed(String handle) {
            return new NodeOutcome(true, handle);
        }
    }

    private static final class RunState {

        private final ExecutionContext context;
        private final Consumer<StepRecord> stepListener;
        private final Map<String, NodeOutcome> outcomes = new ConcurrentHashMap<>();
        private final Map<String, Object> endOutputs = new ConcurrentHashMap<>();
        private final AtomicInteger stepCount = new AtomicInteger();
        private final AtomicReference<String> failure = new AtomicReference<>();
        private final Object listenerLock = new Object();

        private RunState(ExecutionContext context, Consumer<StepRecord> stepListener) {
            this.context = context;
            this.stepListener = stepListener;
        }

        private void record(NodeDef node, NodeResult result, long elapsedMillis) {
            context.getPool().putAll(node.getId(), result.getOutputs());
            synchronized (listenerLock) {
                StepRecord step = StepRecord.completed(node, result, elapsedMillis);
                context.record(step);
                notifyStepListener(stepListener, step);
            }
        }
    }
}
