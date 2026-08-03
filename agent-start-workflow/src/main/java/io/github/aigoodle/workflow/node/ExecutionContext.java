package io.github.aigoodle.workflow.node;

import io.github.aigoodle.workflow.chat.ChatStreamSink;
import io.github.aigoodle.workflow.variable.VariablePool;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-run state shared with every node executor: the variable pool plus run-level
 * identifiers and a step recorder for observability.
 */
@Data
public class ExecutionContext {

    private final VariablePool pool = new VariablePool();
    private String runId;
    private String conversationId;
    private String tenantId;

    /** The inputs the workflow was started with (seeded by the START node). */
    private final Map<String, Object> inputs = new HashMap<>();

    private final List<StepRecord> steps = new ArrayList<>();

    /**
     * Optional token sink threaded from the outer caller (e.g.
     * {@code WorkflowChatGenerator} for SSE chat). Non-null +
     * {@link ChatStreamSink#isStreaming()} = LLM nodes switch to
     * {@code spec.stream().content()} and ANSWER nodes subscribe live;
     * null = classic blocking mode.
     */
    private ChatStreamSink chatSink;

    public static ExecutionContext start(Map<String, Object> inputs, String conversationId,
                                         ChatStreamSink chatSink) {
        ExecutionContext context = new ExecutionContext();
        context.runId = UUID.randomUUID().toString();
        context.conversationId = conversationId;
        context.chatSink = chatSink;
        if (inputs != null) {
            context.inputs.putAll(inputs);
            inputs.forEach(context.pool::setSystem);
        }
        return context;
    }

    public void record(StepRecord step) {
        steps.add(step);
    }
}
