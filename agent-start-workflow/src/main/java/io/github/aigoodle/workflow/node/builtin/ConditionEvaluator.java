package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.variable.VariablePool;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates the boolean expressions attached to IF_ELSE / conditional nodes.
 * <p>
 * A single condition has the shape:
 * <pre>
 *   { "variableSelector": ["2", "struct", "intent_type"],   // designer shape
 *     "operator": "IS",
 *     "value": "greet" }
 * </pre>
 * or the older flat shape:
 * <pre>
 *   { "variable": "2.struct.intent_type", "operator": "is", "value": "greet" }
 * </pre>
 * Both are accepted — {@link #resolvePath} joins the array selector with dots
 * so the path fed to {@link VariablePool#get} is the same string a hand-typed
 * config would produce.
 * <p>
 * <b>Operators</b> are case-insensitive and cover the designer's full vocabulary
 * (Dify parity). Binary ops accept the rendered {@code value}; unary ops
 * ({@code empty} / {@code not_empty} / {@code null} / {@code is_true} / …) ignore
 * it. The {@code in} / {@code not_in} ops honour a raw {@code List} value if the
 * designer sent one, otherwise they split the rendered string on commas — so
 * {@code "greet, chat"} and {@code ["greet","chat"]} both work.
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    /**
     * Evaluate a flat list of conditions combined by {@code and}/{@code or}.
     * Empty list → {@code true} (an empty predicate is trivially satisfied,
     * matching Dify's own semantics — a condition-less case always fires).
     */
    public static boolean evaluate(List<Map<String, Object>> conditions, String logicalOperator, VariablePool pool) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        boolean and = !"or".equalsIgnoreCase(logicalOperator);
        boolean acc = and;
        for (Map<String, Object> c : conditions) {
            boolean r = evaluateOne(c, pool);
            acc = and ? (acc && r) : (acc || r);
            // Short-circuit — a false in AND or a true in OR is final.
            if (and && !acc) return false;
            if (!and && acc) return true;
        }
        return acc;
    }

    /**
     * Evaluate one designer "case" block: {@code {conditions, logicalOperator}}.
     * Convenience over {@link #evaluate} used by IF_ELSE when it walks
     * {@code cases[]} in order.
     */
    @SuppressWarnings("unchecked")
    public static boolean evaluateCase(Map<String, Object> caseDef, VariablePool pool) {
        if (caseDef == null) return false;
        Object conds = caseDef.get("conditions");
        List<Map<String, Object>> conditions = conds instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        String logicalOp = str(caseDef.get("logicalOperator"));
        return evaluate(conditions, logicalOp, pool);
    }

    /**
     * Evaluate a single condition {@code {variable(Selector), operator, value}}.
     * Exposed so specialised nodes (question classifier, list operator) can
     * reuse the operator vocabulary without going through a full case block.
     */
    public static boolean evaluateOne(Map<String, Object> c, VariablePool pool) {
        if (c == null) return false;
        String path = resolvePath(c);
        Object actualObj = path == null ? null : pool.get(path);
        String op = str(c.get("operator"));
        Object rawValue = c.get("value");
        String expected = rawValue instanceof String s
                ? VariableResolver.render(s, pool)
                : rawValue == null ? "" : String.valueOf(rawValue);
        return match(op, actualObj, expected, rawValue);
    }

    /**
     * Turn the designer's {@code variableSelector: ["node", "field", ...]} into
     * the dotted path {@link VariablePool} understands. Falls back to the
     * legacy {@code variable} string when the array shape isn't present.
     * Blank / null segments are skipped so a spurious empty string in the
     * array doesn't produce {@code "..node.field"}.
     */
    private static String resolvePath(Map<String, Object> c) {
        Object sel = c.get("variableSelector");
        if (sel instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object p : list) {
                if (p == null) continue;
                String s = String.valueOf(p).trim();
                if (s.isEmpty()) continue;
                if (sb.length() > 0) sb.append('.');
                sb.append(s);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        Object v = c.get("variable");
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Route unary operators first (they don't care about the {@code value}
     * field), then fall through to the binary switch. The unary ops here
     * mirror what Dify's condition dropdown exposes.
     */
    private static boolean match(String op, Object actualObj, String expected, Object rawValue) {
        String opNorm = op == null ? "equals" : op.trim().toLowerCase(Locale.ROOT);
        return switch (opNorm) {
            case "empty", "is_empty", "isempty" -> isEmpty(actualObj);
            case "not_empty", "is_not_empty", "isnotempty" -> !isEmpty(actualObj);
            case "null", "is_null", "isnull" -> actualObj == null;
            case "not_null", "is_not_null", "isnotnull", "exists" -> actualObj != null;
            case "true", "is_true", "istrue" -> Boolean.TRUE.equals(toBoolean(actualObj));
            case "false", "is_false", "isfalse" -> Boolean.FALSE.equals(toBoolean(actualObj));
            default -> matchBinary(opNorm, actualStr(actualObj), expected, rawValue);
        };
    }

    private static boolean matchBinary(String op, String actual, String expected, Object rawValue) {
        return switch (op) {
            case "equals", "is", "=", "==", "eq" -> actual.equals(expected);
            case "not_equals", "is_not", "isnot", "!=", "<>", "neq", "not_eq" -> !actual.equals(expected);
            case "contains" -> actual.contains(expected);
            case "not_contains", "not_contain", "does_not_contain" -> !actual.contains(expected);
            case "starts_with", "startswith", "starts" -> actual.startsWith(expected);
            case "ends_with", "endswith", "ends" -> actual.endsWith(expected);
            case "gt", ">", "greater", "greater_than" -> compare(actual, expected) > 0;
            case "lt", "<", "less", "less_than" -> compare(actual, expected) < 0;
            case "ge", ">=", "gte", "greater_or_equal", "greater_than_or_equal" -> compare(actual, expected) >= 0;
            case "le", "<=", "lte", "less_or_equal", "less_than_or_equal" -> compare(actual, expected) <= 0;
            case "in" -> inList(actual, rawValue, expected);
            case "not_in", "notin" -> !inList(actual, rawValue, expected);
            case "regex", "matches", "match" -> matchesRegex(actual, expected);
            case "not_regex", "not_matches", "not_match" -> !matchesRegex(actual, expected);
            default -> false;
        };
    }

    /** Compare as numbers when both sides parse; fall back to string order otherwise. */
    private static int compare(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    /**
     * {@code null}, empty string, empty collection, empty map, or empty array
     * all count as "empty". Anything else — including a boolean {@code false}
     * or a numeric {@code 0} — is not empty, matching what the designer's
     * "为空" toggle means to a human user.
     */
    private static boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof CharSequence cs) return cs.length() == 0;
        if (v instanceof Collection<?> c) return c.isEmpty();
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        if (v.getClass().isArray()) return java.lang.reflect.Array.getLength(v) == 0;
        return false;
    }

    /**
     * Best-effort boolean coercion — the pool may hold a {@code Boolean}, or a
     * String from a template render, or an integer flag. Returns {@code null}
     * for unrecognisable inputs so callers can distinguish "not a boolean"
     * from "false".
     */
    private static Boolean toBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "1", "yes", "on" -> Boolean.TRUE;
            case "false", "0", "no", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * Honour a raw {@link List} value first (the designer can send arrays
     * directly), then fall back to splitting the rendered string on commas so
     * {@code value: "greet, chat, help"} works too. Individual entries are
     * trimmed to tolerate spaces after commas.
     */
    private static boolean inList(String actual, Object rawValue, String rendered) {
        if (rawValue instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && String.valueOf(o).equals(actual)) return true;
            }
            return false;
        }
        if (rendered == null || rendered.isEmpty()) return false;
        for (String part : rendered.split(",")) {
            if (part.trim().equals(actual)) return true;
        }
        return false;
    }

    /** Match anywhere in the string (like JS {@code /pattern/.test(s)}). */
    private static boolean matchesRegex(String actual, String pattern) {
        if (pattern == null || pattern.isEmpty()) return false;
        try {
            return Pattern.compile(pattern).matcher(actual).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static String actualStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
