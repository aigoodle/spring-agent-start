package io.github.aigoodle.workflow.graph;

import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in a workflow definition: an id, a type and a free-form config map
 * ({@code data}) interpreted by the node's executor.
 */
@Data
public class NodeDef {

    private String id;
    private NodeType type;
    private String title;

    /** Executor-specific configuration. */
    private Map<String, Object> data = new HashMap<>();

    public Object get(String key) {
        return data.get(key);
    }

    public String getString(String key) {
        Object v = data.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String getString(String key, String defaultValue) {
        String v = getString(key);
        return v == null ? defaultValue : v;
    }

    public int getInt(String key, int defaultValue) {
        Object v = data.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? defaultValue : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMapList(String key) {
        Object v = data.get(key);
        return v instanceof List ? (List<Map<String, Object>>) v : List.of();
    }

    public static NodeDef of(String id, NodeType type) {
        NodeDef n = new NodeDef();
        n.setId(id);
        n.setType(type);
        return n;
    }

    public NodeDef with(String key, Object value) {
        this.data.put(key, value);
        return this;
    }
}
