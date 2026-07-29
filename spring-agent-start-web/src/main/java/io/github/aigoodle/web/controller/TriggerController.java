package io.github.aigoodle.web.controller;

import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.trigger.service.CreateTriggerRequest;
import io.github.aigoodle.trigger.service.TriggerService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST facade over {@link TriggerService}. Manage webhook / cron / event triggers,
 * fire them manually, list past invocations and replay one. Wired only when the
 * trigger module is present.
 */
@RestController
@ConditionalOnBean(TriggerService.class)
@RequestMapping("${spring-agent.web.base-path:}")
public class TriggerController {

    private final TriggerService triggerService;

    public TriggerController(TriggerService triggerService) {
        this.triggerService = triggerService;
    }

    // ------------------------------------------------------------------ CRUD

    @GetMapping("/triggers")
    public ApiResponse<List<TriggerEntity>> list(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(triggerService.list(tenantId));
    }

    @GetMapping("/triggers/{id}")
    public ApiResponse<TriggerEntity> get(@PathVariable String id) {
        return ApiResponse.ok(triggerService.require(id));
    }

    @PostMapping("/triggers")
    public ApiResponse<TriggerEntity> create(@RequestBody CreateTriggerRequest req) {
        return ApiResponse.ok(triggerService.create(req));
    }

    @PutMapping("/triggers/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        triggerService.setEnabled(id, enabled);
        return ApiResponse.ok();
    }

    @DeleteMapping("/triggers/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        triggerService.delete(id);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------- firing

    /**
     * Fire a trigger manually — useful for the frontend "test" button. Runs sync so
     * the UI can render the outcome immediately.
     */
    @PostMapping("/triggers/{id}/fire")
    public ApiResponse<DispatchResult> fire(@PathVariable String id,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(triggerService.fireSync(id, payload == null ? Map.of() : payload, "manual"));
    }

    // -------------------------------------------------------- invocations

    @GetMapping("/triggers/{id}/invocations")
    public ApiResponse<List<TriggerInvocationEntity>> invocations(@PathVariable String id) {
        return ApiResponse.ok(triggerService.invocations(id));
    }

    @GetMapping("/invocations/{id}")
    public ApiResponse<TriggerInvocationEntity> invocation(@PathVariable String id) {
        return ApiResponse.ok(triggerService.invocation(id));
    }

    @PostMapping("/invocations/{id}/replay")
    public ApiResponse<TriggerInvocationEntity> replay(@PathVariable String id) {
        return ApiResponse.ok(triggerService.replay(id));
    }
}
