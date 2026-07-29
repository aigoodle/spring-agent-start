package io.github.aigoodle.trigger.service;

import io.github.aigoodle.trigger.api.TriggerType;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Input for creating a trigger.
 */
@Data
@Builder
public class CreateTriggerRequest {

    private String tenantId;
    private String name;
    private TriggerType type;

    @Builder.Default
    private String targetType = "workflow";

    private String targetId;

    /** Type-specific config: webhook {@code path}/{@code token}, cron {@code expression}, event {@code eventName}. */
    @Builder.Default
    private Map<String, Object> config = new HashMap<>();

    @Builder.Default
    private boolean enabled = true;
}
