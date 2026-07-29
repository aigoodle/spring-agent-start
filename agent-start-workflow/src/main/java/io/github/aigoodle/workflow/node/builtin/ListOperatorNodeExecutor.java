package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Applies list-level operations to a variable pointing at a {@code List}. Supports
 * {@code filter}, {@code sort} and {@code limit}. Config:
 * <ul>
 *   <li>{@code inputList} — dotted path in the pool, e.g. {@code "http.items"}</li>
 *   <li>{@code operation} — one of {@code filter|sort|limit} (default {@code limit})</li>
 *   <li>{@code field} — for filter/sort: the map key inside each item</li>
 *   <li>{@code condition}/{@code value} — for filter: only kept if {@code item.field == value}</li>
 *   <li>{@code order} — for sort: {@code asc}/{@code desc}</li>
 *   <li>{@code size} — for limit: max items</li>
 * </ul>
 * Output: {@code result} (transformed list).
 */
public class ListOperatorNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.LIST_OPERATOR;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String inputRef = node.getString("inputList");
        Object raw = inputRef == null ? null : ctx.getPool().get(inputRef);
        if (!(raw instanceof List<?> list)) {
            return NodeResult.of("result", List.of());
        }
        String op = node.getString("operation", "limit").toLowerCase();
        List<Object> working = new ArrayList<>(list);
        return NodeResult.of("result", switch (op) {
            case "filter" -> filter(working, node.getString("field"), node.getString("value"));
            case "sort" -> sort(working, node.getString("field"), node.getString("order", "asc"));
            case "limit" -> limit(working, node.getInt("size", 10));
            case "distinct" -> distinct(working);
            default -> working;
        });
    }

    private static List<Object> filter(List<Object> items, String field, String value) {
        if (field == null || value == null) {
            return items;
        }
        List<Object> kept = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                Object actual = map.get(field);
                if (actual != null && String.valueOf(actual).equals(value)) {
                    kept.add(item);
                }
            } else if (String.valueOf(item).equals(value)) {
                kept.add(item);
            }
        }
        return kept;
    }

    private static List<Object> sort(List<Object> items, String field, String order) {
        boolean desc = "desc".equalsIgnoreCase(order);
        Comparator<Object> cmp = (a, b) -> {
            Object va = field == null ? a : (a instanceof Map<?, ?> ma ? ma.get(field) : a);
            Object vb = field == null ? b : (b instanceof Map<?, ?> mb ? mb.get(field) : b);
            return compare(va, vb);
        };
        items.sort(desc ? cmp.reversed() : cmp);
        return items;
    }

    private static List<Object> limit(List<Object> items, int size) {
        if (size <= 0 || size >= items.size()) {
            return items;
        }
        return items.subList(0, size);
    }

    private static List<Object> distinct(List<Object> items) {
        List<Object> out = new ArrayList<>();
        for (Object item : items) {
            if (!out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable ca && a.getClass().equals(b.getClass())) {
            return ca.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
