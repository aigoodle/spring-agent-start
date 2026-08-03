package io.github.aigoodle.workflow.variable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime variable store of a workflow execution. Holds system variables
 * (under the {@code sys} namespace) and the outputs of every executed node, keyed by
 * node id. Values are addressed by dotted path: {@code "sys.query"},
 * {@code "llm.text"}, {@code "http.body"}.
 * <p>
 * Backed by {@link ConcurrentHashMap} so worker threads running parallel nodes can
 * safely read while the engine's coordinator writes another node's outputs.
 */
public class VariablePool {

    public static final String SYS = "sys";

    /** namespace -> (key -> value); namespace is a node id or {@code sys}. */
    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    public void setSystem(String key, Object value) {
        put(SYS, key, value);
    }

    public void put(String namespace, String key, Object value) {
        if (value == null) {
            // ConcurrentHashMap forbids null values; get() on a missing key returns null,
            // so dropping the write preserves the observable behaviour of "read returns null".
            return;
        }
        store.computeIfAbsent(namespace, ignored -> new ConcurrentHashMap<>()).put(key, value);
    }

    public void putAll(String namespace, Map<String, Object> values) {
        if (values != null && !values.isEmpty()) {
            Map<String, Object> namespaceValues =
                    store.computeIfAbsent(namespace, ignored -> new ConcurrentHashMap<>());
            values.forEach((key, value) -> {
                if (value != null) {
                    namespaceValues.put(key, value);
                }
            });
        }
    }

    public Map<String, Object> namespace(String namespace) {
        return store.getOrDefault(namespace, Map.of());
    }

    /** Resolve a dotted path like {@code "node.field"} or {@code "node.field.sub"}. */
    public Object get(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.", 2);
        if (parts.length < 2) {
            return null;
        }
        Map<String, Object> namespaceValues = store.get(parts[0]);
        if (namespaceValues == null) {
            return null;
        }
        String rest = parts[1];
        if (namespaceValues.containsKey(rest)) {
            return namespaceValues.get(rest);
        }
        // descend into nested maps for "field.sub"
        String[] segments = rest.split("\\.");
        Object current = namespaceValues.get(segments[0]);
        for (int index = 1; index < segments.length && current instanceof Map<?, ?> nestedMap; index++) {
            current = nestedMap.get(segments[index]);
        }
        return current;
    }

    public String getString(String path) {
        Object value = get(path);
        return value == null ? null : String.valueOf(value);
    }

    public Map<String, Map<String, Object>> snapshot() {
        return store;
    }
}
