package io.github.aigoodle.tool;

import java.util.Map;

/**
 * Convenience base with typed argument helpers.
 */
public abstract class AbstractAgentTool implements AgentTool {

    protected String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    protected String str(Map<String, Object> args, String key, String defaultValue) {
        String v = str(args, key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    protected double dbl(Map<String, Object> args, String key, double defaultValue) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? defaultValue : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
