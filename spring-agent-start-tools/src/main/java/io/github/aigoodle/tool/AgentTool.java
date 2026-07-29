package io.github.aigoodle.tool;

import java.util.Map;

/**
 * A callable capability an agent or workflow can invoke. Implementations are
 * discovered as Spring beans (or contributed by a {@link ToolProvider}), adapted to a
 * Spring AI {@code ToolCallback}, and offered to the model for function calling.
 * <p>
 * This is the n8n-style "node"/connector unit: name + description + input schema +
 * an execute method. Keep {@link #execute} side-effect-honest and return a value the
 * model can read (a String or a JSON-serialisable object).
 */
public interface AgentTool {

    /** Unique, model-facing tool name (snake_case recommended). */
    String name();

    /** What the tool does and when to use it — the model reads this to decide. */
    String description();

    /**
     * JSON Schema (as a string) describing the {@code execute} arguments. Defaults to
     * an open object. Used by the model to format tool calls.
     */
    default String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}";
    }

    /** Run the tool. {@code args} are the parsed JSON arguments from the model. */
    Object execute(Map<String, Object> args);
}
