package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndNodeExecutorTest {

    @Test
    void resolvesDesignerOutputListFromVariableSelector() {
        ExecutionContext context = ExecutionContext.start(Map.of(), null, null);
        Map<String, Object> structured = Map.of("items", List.of("one", "two"));
        context.getPool().put("llm", "text", "介绍内容");
        context.getPool().put("llm", "structured", structured);
        NodeDef end = NodeDef.of("end", NodeType.END).with("output", List.of(
                Map.of("name", "introduction", "type", "string",
                        "variableSelector", List.of("llm", "text")),
                Map.of("name", "details", "type", "object",
                        "variableSelector", List.of("llm", "structured"))));

        NodeResult result = new EndNodeExecutor().execute(end, context);

        assertEquals("介绍内容", result.getOutputs().get("introduction"));
        assertEquals(structured, result.getOutputs().get("details"));
    }

    @Test
    void keepsLegacyOutputsMapCompatible() {
        ExecutionContext context = ExecutionContext.start(Map.of(), null, null);
        context.getPool().put("llm", "text", "hello");
        NodeDef end = NodeDef.of("end", NodeType.END)
                .with("outputs", Map.of("answer", "Result: {{#llm.text#}}"));

        NodeResult result = new EndNodeExecutor().execute(end, context);

        assertEquals("Result: hello", result.getOutputs().get("answer"));
    }
}
