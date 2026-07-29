package io.github.aigoodle.workflow.graph;

import lombok.Data;

/**
 * A directed edge. {@code sourceHandle} selects which branch of a multi-output node
 * (e.g. IF_ELSE's {@code true}/{@code false}) this edge belongs to; null means the
 * node's default/only output.
 */
@Data
public class EdgeDef {

    private String source;
    private String target;
    private String sourceHandle;

    public static EdgeDef of(String source, String target) {
        EdgeDef e = new EdgeDef();
        e.setSource(source);
        e.setTarget(target);
        return e;
    }

    public static EdgeDef of(String source, String target, String sourceHandle) {
        EdgeDef e = of(source, target);
        e.setSourceHandle(sourceHandle);
        return e;
    }
}
