package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Runs a JEXL expression / script with access to the current variable pool. Config:
 * <ul>
 *   <li>{@code code} — a JEXL script (last expression = return value)</li>
 *   <li>{@code outputKey} — where to store the return value (default {@code result})</li>
 * </ul>
 * The script sees:
 * <ul>
 *   <li>{@code sys.*} — run-level inputs</li>
 *   <li>{@code &lt;nodeId&gt;.*} — every executed node's outputs</li>
 * </ul>
 * Only registered when JEXL is on the classpath. Kept intentionally sandbox-lite:
 * JEXL 3 already forbids `Runtime.exec` etc. by default; applications embedding this
 * should keep the code behind trusted authoring.
 */
public class CodeNodeExecutor implements NodeExecutor {

    private final JexlEngine jexl = new JexlBuilder().safe(true).silent(true).strict(false).create();

    @Override
    public NodeType type() {
        return NodeType.CODE;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String code = node.getString("code");
        if (code == null || code.isBlank()) {
            return NodeResult.failure("Code node requires 'code'");
        }
        try {
            JexlScript script = jexl.createScript(code);
            JexlContext jctx = buildContext(ctx);
            Object out = script.execute(jctx);
            return NodeResult.of(node.getString("outputKey", "result"), out);
        } catch (Exception e) {
            return NodeResult.failure("Code node failed: " + e.getMessage());
        }
    }

    private static JexlContext buildContext(ExecutionContext ctx) {
        MapContext jctx = new MapContext();
        Map<String, Map<String, Object>> snapshot = ctx.getPool().snapshot();
        for (Map.Entry<String, Map<String, Object>> e : snapshot.entrySet()) {
            jctx.set(e.getKey(), new HashMap<>(e.getValue()));
        }
        jctx.set("inputs", new HashMap<>(ctx.getInputs()));
        return jctx;
    }
}
