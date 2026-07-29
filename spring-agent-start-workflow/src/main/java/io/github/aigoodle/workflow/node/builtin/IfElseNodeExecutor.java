package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;

import java.util.List;
import java.util.Map;

/**
 * Branches the workflow with either the designer's <b>cases[]</b> shape
 * (arbitrary number of elif-style branches + implicit else) or the older
 * <b>flat conditions</b> shape (single true/false split).
 *
 * <h4>cases[] shape (Dify designer)</h4>
 * <pre>
 * {
 *   "cases": [
 *     { "id": "case_abc", "caseId": "true", "logicalOperator": "or",
 *       "conditions": [
 *         {"variableSelector": ["2","struct","intent_type"], "operator": "IS", "value": "greet"},
 *         {"variableSelector": ["2","struct","intent_type"], "operator": "CONTAINS", "value": "chat"}
 *       ] },
 *     { "id": "case_def", "caseId": "true", "logicalOperator": "and",
 *       "conditions": [ {"variableSelector": ["2","struct","intent_type"], "operator": "IS", "value": "exit"} ] }
 *   ]
 * }
 * </pre>
 * Cases are evaluated in order; <b>the first case that matches wins</b> — its
 * {@code id} becomes the node's {@code handle}, which the engine matches
 * against outgoing edges via {@code sourceHandle}. Only that branch fires;
 * the rest of the cases (and their downstream chains) are skipped.
 * <p>
 * If no case matches, the executor emits {@code "false"} as an implicit else
 * handle so a graph author can wire a fallback edge with
 * {@code sourceHandle="false"} from the node — the same convention the old
 * flat shape uses for its negative branch, so both shapes cohabit safely.
 *
 * <h4>Flat shape (backward-compat)</h4>
 * <pre>
 * { "conditions": [...], "logicalOperator": "and" }
 * </pre>
 * Evaluated as one predicate, emits {@code "true"} or {@code "false"} handle.
 *
 * <h4>Outputs</h4>
 * <ul>
 *   <li>{@code handle} — the outgoing edge selector (case id / true / false)</li>
 *   <li>{@code case} — the matched case's {@code caseId} label, or
 *       {@code "false"} when nothing matched</li>
 *   <li>{@code caseIndex} — 0-based index of the matched case, or {@code -1}
 *       for the else branch (useful when authors want to debug or route
 *       downstream by index)</li>
 *   <li>{@code result} — boolean match flag for the flat shape</li>
 * </ul>
 */
public class IfElseNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.IF_ELSE;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        List<Map<String, Object>> cases = node.getMapList("cases");
        if (cases != null && !cases.isEmpty()) {
            return evaluateCases(cases, ctx);
        }
        // Legacy flat shape — kept alive so old saved graphs / tests still work.
        boolean matched = ConditionEvaluator.evaluate(
                node.getMapList("conditions"),
                node.getString("logicalOperator", "and"),
                ctx.getPool());
        return NodeResult.empty()
                .output("result", matched)
                .output("case", matched ? "true" : "false")
                .handle(matched ? "true" : "false");
    }

    private static NodeResult evaluateCases(List<Map<String, Object>> cases, ExecutionContext ctx) {
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> c = cases.get(i);
            if (ConditionEvaluator.evaluateCase(c, ctx.getPool())) {
                String handle = firstString(c, "id", "caseId");
                String caseLabel = firstString(c, "caseId", "id");
                return NodeResult.empty()
                        .output("case", caseLabel != null ? caseLabel : "true")
                        .output("caseIndex", i)
                        .output("result", true)
                        .handle(handle != null ? handle : "true");
            }
        }
        // No case matched — implicit else. Emit "false" so a designer can
        // wire an edge with sourceHandle="false" as the catch-all branch.
        return NodeResult.empty()
                .output("case", "false")
                .output("caseIndex", -1)
                .output("result", false)
                .handle("false");
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }
}
