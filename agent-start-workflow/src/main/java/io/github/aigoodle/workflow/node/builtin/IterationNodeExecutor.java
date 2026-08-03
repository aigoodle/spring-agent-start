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
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        Object inputValue = resolveInputValue(node, context);
        if (!(inputValue instanceof List<?> items)) {
            return NodeResult.failure("Iteration 'inputList' must resolve to a list");
        }

        WorkflowGraph subGraph = parseSubGraph(node.get("subGraph"));
        if (subGraph == null || subGraph.getNodes().isEmpty()) {
            return NodeResult.failure("Iteration requires a non-empty 'subGraph'");
        }

        IterationConfiguration configuration = IterationConfiguration.from(node);
        WorkflowEngine workflowEngine = engineSupplier.get();
        List<Object> collectedOutputs = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> iterationInputs = configuration.inputsFor(items.get(index), index);
            WorkflowRunResult iterationResult = workflowEngine.run(
                    subGraph, iterationInputs, context.getConversationId());
            if (iterationResult.isSuccess()) {
                collectedOutputs.add(iterationResult.getOutputs());
                continue;
            }
            if (configuration.continueOnError()) {
                collectedOutputs.add(null);
                continue;
            }
            return NodeResult.failure(
                    "Iteration failed at index " + index + ": " + iterationResult.getError());
        }
        return NodeResult.of(configuration.outputVariable(), collectedOutputs);
    }

    private static Object resolveInputValue(NodeDef node, ExecutionContext context) {
        String inputReference = node.getString("inputList");
        return inputReference == null ? null : context.getPool().get(inputReference);
    }

    private static WorkflowGraph parseSubGraph(Object configuredGraph) {
        if (configuredGraph == null) {
            return null;
        }
        if (configuredGraph instanceof WorkflowGraph graph) {
            return graph;
        }
        // Config maps come from JSON, so round-trip through JsonUtils to bind the shape.
        String graphJson = configuredGraph instanceof String json
                ? json
                : JsonUtils.toJson(configuredGraph);
        return JsonUtils.parse(graphJson, WorkflowGraph.class);
    }

}
