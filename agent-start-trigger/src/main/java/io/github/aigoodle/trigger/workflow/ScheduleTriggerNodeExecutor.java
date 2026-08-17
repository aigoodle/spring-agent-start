package io.github.aigoodle.trigger.workflow;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.service.CreateTriggerRequest;
import io.github.aigoodle.trigger.service.TriggerService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/** Workflow node that turns LLM-produced schedule JSON into a persisted timer. */
public class ScheduleTriggerNodeExecutor implements NodeExecutor {

    private final ObjectProvider<TriggerService> triggerServiceProvider;

    public ScheduleTriggerNodeExecutor(ObjectProvider<TriggerService> triggerServiceProvider) {
        this.triggerServiceProvider = triggerServiceProvider;
    }

    @Override
    public NodeType type() {
        return NodeType.SCHEDULE_TRIGGER;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        try {
            String targetWorkflowId = required(node.getString("targetWorkflowId"), "targetWorkflowId");
            Map<String, Object> schedule = schedule(node.get("schedule"));
            Object payload = node.get("payload");
            if (payload instanceof Map<?, ?> map) schedule.put("payload", stringKeyMap(map));
            schedule.putIfAbsent("conversationId", context.getConversationId());
            TriggerEntity created = triggerServiceProvider.getObject().create(
                    CreateTriggerRequest.builder()
                            .tenantId(context.getTenantId())
                            .name(node.getString("name", "Workflow scheduled task"))
                            .type(TriggerType.CRON)
                            .targetType("workflow")
                            .targetId(targetWorkflowId)
                            .config(schedule)
                            .enabled(true)
                            .build());
            return NodeResult.empty()
                    .output("triggerId", created.getId())
                    .output("nextFireAt", created.getNextFireAt())
                    .output("targetWorkflowId", created.getTargetId());
        } catch (RuntimeException exception) {
            return NodeResult.failure(exception.getMessage());
        }
    }

    private static Map<String, Object> schedule(Object value) {
        if (value instanceof Map<?, ?> map) return stringKeyMap(map);
        if (value instanceof String json) return new LinkedHashMap<>(JsonUtils.parseMap(json));
        throw new IllegalArgumentException("schedule must be a JSON object or JSON string");
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
