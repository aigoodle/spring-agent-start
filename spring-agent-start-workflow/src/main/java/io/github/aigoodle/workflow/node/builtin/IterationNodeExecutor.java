package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.workflow.engine.WorkflowEngine;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs a sub-workflow once per element of a list variable. Config:
 * <ul>
 *   <li>{@code inputList} — dotted pool path resolving to a {@code List}</li>
 *   <li>{@code subGraph} — an inline {@link WorkflowGraph} definition (as a Map)</li>
 *   <li>{@code itemKey} / {@code indexKey} — names used to expose the current item /
 *       zero-based index to the sub-graph via its {@code sys} namespace
 *       (defaults: {@code item} / {@code index})</li>
 *   <li>{@code outputKey} — where to store the aggregated list of sub-run outputs
 *       (default {@code output})</li>
 * </ul>
 * A failed sub-run fails the iteration node; use {@code continueOnError=true} to
 * keep going and collect nulls in the aggregated output.
 * <p>
 * WorkflowEngine is injected lazily to break the DI cycle (executors are collected
 * into the registry the engine uses).
 */
public class IterationNodeExecutor implements NodeExecutor {

    private final Supplier<WorkflowEngine> engineSupplier;

    public IterationNodeExecutor(Supplier<WorkflowEngine> engineSupplier) {
        this.engineSupplier = engineSupplier;
    }

    @Override
    public NodeType type() {
        return NodeType.ITERATION;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String inputRef = node.getString("inputList");
        Object listObj = inputRef == null ? null : ctx.getPool().get(inputRef);
        if (!(listObj instanceof List<?> list)) {
            return NodeResult.failure("Iteration 'inputList' must resolve to a list");
        }
        Object graphObj = node.get("subGraph");
        WorkflowGraph subGraph = parseSubGraph(graphObj);
        if (subGraph == null || subGraph.getNodes().isEmpty()) {
            return NodeResult.failure("Iteration requires a non-empty 'subGraph'");
        }
        String itemKey = node.getString("itemKey", "item");
        String indexKey = node.getString("indexKey", "index");
        boolean continueOnError = "true".equalsIgnoreCase(node.getString("continueOnError", "false"));

        WorkflowEngine engine = engineSupplier.get();
        List<Object> aggregated = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put(itemKey, list.get(i));
            inputs.put(indexKey, i);
            WorkflowRunResult r = engine.run(subGraph, inputs, ctx.getConversationId());
            if (r.isSuccess()) {
                aggregated.add(r.getOutputs());
            } else if (continueOnError) {
                aggregated.add(null);
            } else {
                return NodeResult.failure("Iteration failed at index " + i + ": " + r.getError());
            }
        }
        return NodeResult.of(node.getString("outputKey", "output"), aggregated);
    }

    @SuppressWarnings("unchecked")
    private static WorkflowGraph parseSubGraph(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof WorkflowGraph g) {
            return g;
        }
        // Config maps come from JSON, so round-trip through JsonUtils to bind the shape.
        String json = raw instanceof String s ? s : JsonUtils.toJson(raw);
        return JsonUtils.parse(json, WorkflowGraph.class);
    }
}
