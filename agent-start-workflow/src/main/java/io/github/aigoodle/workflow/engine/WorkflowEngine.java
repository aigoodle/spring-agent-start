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
import java.util.UUID;
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

    private final NodeExecutorRegistry registry;

    public WorkflowEngine(NodeExecutorRegistry registry) {
        this.registry = registry;
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
        ExecutionContext ctx = new ExecutionContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setConversationId(conversationId);
        ctx.setChatSink(chatSink);
        if (inputs != null) {
            ctx.getInputs().putAll(inputs);
            inputs.forEach(ctx.getPool()::setSystem);
        }

        WorkflowRunResult result = new WorkflowRunResult();
        result.setRunId(ctx.getRunId());
        result.setSteps(ctx.getSteps());
        Map<String, Object> endOutputs = new ConcurrentHashMap<>();

        NodeDef startNode = graph.startNode();
        Map<String, List<EdgeDef>> incomingByTarget = indexIncoming(graph);

        Map<String, NodeOutcome> outcomes = new ConcurrentHashMap<>();
        Map<String, CompletableFuture<Void>> nodeFutures = new HashMap<>();
        for (NodeDef n : graph.getNodes()) {
            nodeFutures.put(n.getId(), new CompletableFuture<>());
        }

        AtomicInteger stepCount = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();
        Object listenerLock = new Object();

        // A per-run virtual-thread executor: nodes are typically I/O-bound (HTTP,
        // LLM, retrieval), so per-task virtual threads give ideal parallelism
        // without needing a bounded pool tuned for blocking.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (NodeDef n : graph.getNodes()) {
                scheduleNode(n, startNode, incomingByTarget.getOrDefault(n.getId(), List.of()),
                        nodeFutures, outcomes, endOutputs, stepCount, failure, listenerLock,
                        ctx, stepListener, executor);
            }

            try {
                CompletableFuture.allOf(nodeFutures.values().toArray(new CompletableFuture[0])).join();
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.error("Workflow run {} failed: {}", ctx.getRunId(), cause.getMessage(), cause);
                result.setSuccess(false);
                result.setError(cause.getMessage());
                result.setOutputs(endOutputs);
                return result;
            } catch (Exception ex) {
                log.error("Workflow run {} failed: {}", ctx.getRunId(), ex.getMessage(), ex);
                result.setSuccess(false);
                result.setError(ex.getMessage());
                result.setOutputs(endOutputs);
                return result;
            }
        }

        String err = failure.get();
        if (err != null) {
            result.setSuccess(false);
            result.setError(err);
        } else {
            result.setSuccess(true);
        }
        result.setOutputs(endOutputs);
        return result;
    }

    private void scheduleNode(NodeDef node, NodeDef startNode, List<EdgeDef> incoming,
                              Map<String, CompletableFuture<Void>> nodeFutures,
                              Map<String, NodeOutcome> outcomes,
                              Map<String, Object> endOutputs,
                              AtomicInteger stepCount,
                              AtomicReference<String> failure,
                              Object listenerLock,
                              ExecutionContext ctx,
                              Consumer<StepRecord> stepListener,
                              Executor executor) {
        boolean isStart = node.getId().equals(startNode.getId());
        CompletableFuture<Void> self = nodeFutures.get(node.getId());

        Runnable task = () -> runOne(node, isStart, incoming, outcomes, endOutputs,
                stepCount, failure, listenerLock, ctx, stepListener, self);

        if (isStart) {
            executor.execute(task);
            return;
        }
        if (incoming.isEmpty()) {
            // Orphan node: never reachable from START, so skip and let the run finish.
            outcomes.put(node.getId(), NodeOutcome.skipped());
            self.complete(null);
            return;
        }
        CompletableFuture<?>[] parents = incoming.stream()
                .map(e -> nodeFutures.get(e.getSource()))
                .filter(Objects::nonNull)
                .distinct()
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(parents).thenRunAsync(task, executor);
    }

    private void runOne(NodeDef node, boolean isStart, List<EdgeDef> incoming,
                        Map<String, NodeOutcome> outcomes,
                        Map<String, Object> endOutputs,
                        AtomicInteger stepCount,
                        AtomicReference<String> failure,
                        Object listenerLock,
                        ExecutionContext ctx,
                        Consumer<StepRecord> stepListener,
                        CompletableFuture<Void> self) {
        try {
            boolean fired = isStart || anyIncomingFired(incoming, outcomes);
            if (!fired || failure.get() != null) {
                outcomes.put(node.getId(), NodeOutcome.skipped());
                return;
            }
            if (stepCount.incrementAndGet() > MAX_STEPS) {
                failure.compareAndSet(null,
                        new AgentException("max_steps",
                                "Workflow exceeded " + MAX_STEPS + " steps", null).getMessage());
                outcomes.put(node.getId(), NodeOutcome.skipped());
                return;
            }

            long start = System.nanoTime();
            NodeResult nr;
            try {
                NodeExecutor exec = registry.get(node.getType());
                nr = exec.execute(node, ctx);
            } catch (Exception e) {
                log.error("Node {} ({}) failed: {}", node.getId(), node.getType(), e.getMessage(), e);
                nr = NodeResult.failure(e.getMessage());
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            ctx.getPool().putAll(node.getId(), nr.getOutputs());
            synchronized (listenerLock) {
                StepRecord rec = record(ctx, node, nr, elapsed);
                fire(stepListener, rec);
            }

            if (nr.isFailed()) {
                failure.compareAndSet(null, "Node " + node.getId() + " failed: " + nr.getError());
                outcomes.put(node.getId(), NodeOutcome.executed(nr.getHandle()));
                return;
            }
            if (node.getType() == NodeType.END && nr.getOutputs() != null) {
                endOutputs.putAll(nr.getOutputs());
            }
            outcomes.put(node.getId(), NodeOutcome.executed(nr.getHandle()));
        } finally {
            self.complete(null);
        }
    }

    private static boolean anyIncomingFired(List<EdgeDef> incoming, Map<String, NodeOutcome> outcomes) {
        for (EdgeDef e : incoming) {
            NodeOutcome parent = outcomes.get(e.getSource());
            if (parent != null && parent.executed() && matches(e, parent.handle())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<EdgeDef>> indexIncoming(WorkflowGraph graph) {
        Map<String, List<EdgeDef>> incoming = new HashMap<>();
        for (NodeDef n : graph.getNodes()) {
            incoming.put(n.getId(), new ArrayList<>());
        }
        for (EdgeDef e : graph.getEdges()) {
            List<EdgeDef> list = incoming.get(e.getTarget());
            if (list != null) {
                list.add(e);
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

    private static StepRecord record(ExecutionContext ctx, NodeDef node, NodeResult nr, long elapsed) {
        StepRecord rec = new StepRecord();
        rec.setNodeId(node.getId());
        rec.setNodeType(node.getType());
        rec.setTitle(node.getTitle());
        rec.setOutputs(nr.getOutputs());
        rec.setHandle(nr.getHandle());
        rec.setElapsedMillis(elapsed);
        rec.setFailed(nr.isFailed());
        rec.setError(nr.getError());
        ctx.record(rec);
        return rec;
    }

    private static void fire(Consumer<StepRecord> listener, StepRecord rec) {
        if (listener == null) {
            return;
        }
        try {
            listener.accept(rec);
        } catch (Exception e) {
            log.warn("Workflow step listener threw: {}", e.getMessage());
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
}
