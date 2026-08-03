package io.github.aigoodle.example;

import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.trigger.service.CreateTriggerRequest;
import io.github.aigoodle.trigger.service.TriggerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates triggers/automation. The actual inbound webhook is served by the trigger
 * module at {@code POST /triggers/webhook/{path}}; here we create triggers and inspect
 * their invocation history / replay them.
 */
@RestController
public class AutomationDemoController {

    private final TriggerService triggerService;

    public AutomationDemoController(TriggerService triggerService) {
        this.triggerService = triggerService;
    }

    /**
     * Create a webhook trigger. {@code targetType} is {@code workflow} or {@code agent}
     * (the latter via the app's AgentTriggerDispatcher). Then call
     * {@code POST /triggers/webhook/{path}?token=...} to fire it.
     */
    @PostMapping("/automation/webhook")
    public Map<String, Object> createWebhook(@RequestParam String name,
                                             @RequestParam String targetType,
                                             @RequestParam String targetId,
                                             @RequestParam String path,
                                             @RequestParam(required = false) String token) {
        TriggerEntity trigger = triggerService.create(CreateTriggerRequest.builder()
                .name(name).type(TriggerType.WEBHOOK)
                .targetType(targetType).targetId(targetId)
                .config(token == null ? Map.of("path", path) : Map.of("path", path, "token", token))
                .enabled(true).build());
        return Map.of(
                "triggerId", trigger.getId(),
                "webhookUrl", "/triggers/webhook/" + path);
    }

    @GetMapping("/automation/triggers/{id}/invocations")
    public List<TriggerInvocationEntity> invocations(@PathVariable String id) {
        return triggerService.invocations(id);
    }

    @PostMapping("/automation/invocations/{id}/replay")
    public TriggerInvocationEntity replay(@PathVariable String id) {
        return triggerService.replay(id);
    }
}
