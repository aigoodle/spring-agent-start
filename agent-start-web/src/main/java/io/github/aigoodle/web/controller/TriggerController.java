package io.github.aigoodle.web.controller;

import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.trigger.service.CreateTriggerRequest;
import io.github.aigoodle.trigger.service.TriggerInvocationRequest;
import io.github.aigoodle.trigger.service.TriggerService;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

/**
 * REST facade over {@link TriggerService}. Manage webhook / cron / event triggers,
 * fire them manually, list past invocations and replay one. Wired only when the
 * trigger module is present.
 */
@RestController
@ConditionalOnBean(TriggerService.class)
public class TriggerController {

    private final TriggerService triggerService;

    public TriggerController(TriggerService triggerService) {
        this.triggerService = triggerService;
    }

    // ------------------------------------------------------------------ CRUD

    @GetMapping("/triggers")
    public ApiResponse<List<TriggerEntity>> list() {
        return ApiResponse.ok(triggerService.list(currentTenantId()));
    }

    @GetMapping("/triggers/{id}")
    public ApiResponse<TriggerEntity> get(@PathVariable String id) {
        return ApiResponse.ok(triggerService.require(currentTenantId(), id));
    }

    @PostMapping("/triggers")
    public ApiResponse<TriggerEntity> create(@RequestBody CreateTriggerRequest request) {
        request.setTenantId(currentTenantId());
        return ApiResponse.ok(triggerService.create(request));
    }

    /** LLM/tool-friendly endpoint: accepts the task definition as a JSON string. */
    @PostMapping(value = "/triggers/from-json", consumes = "text/plain")
    public ApiResponse<TriggerEntity> createFromJson(@RequestBody String json) {
        CreateTriggerRequest request = JsonUtils.parse(json, CreateTriggerRequest.class);
        request.setTenantId(currentTenantId());
        return ApiResponse.ok(triggerService.create(request));
    }

    @PutMapping("/triggers/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        triggerService.setEnabled(currentTenantId(), id, enabled);
        return ApiResponse.ok();
    }

    @DeleteMapping("/triggers/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        triggerService.delete(currentTenantId(), id);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------- firing

    /**
     * Fire a trigger manually — useful for the frontend "test" button. The target
     * always runs asynchronously; callers can inspect invocation history for status.
     */
    @PostMapping("/triggers/{id}/fire")
    public ApiResponse<DispatchResult> fire(@PathVariable String id,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        String invocationId = triggerService.fireAsynchronously(currentTenantId(),
                TriggerInvocationRequest.manual(id, payload));
        return ApiResponse.ok(DispatchResult.ok(null, Map.of(
                "accepted", true, "invocationId", invocationId)));
    }

    // -------------------------------------------------------- invocations

    @GetMapping("/triggers/{id}/invocations")
    public ApiResponse<List<TriggerInvocationEntity>> invocations(@PathVariable String id) {
        return ApiResponse.ok(triggerService.invocations(currentTenantId(), id));
    }

    @GetMapping("/invocations/{id}")
    public ApiResponse<TriggerInvocationEntity> invocation(@PathVariable String id) {
        return ApiResponse.ok(triggerService.invocation(currentTenantId(), id));
    }

    @PostMapping("/invocations/{id}/replay")
    public ApiResponse<TriggerInvocationEntity> replay(@PathVariable String id) {
        return ApiResponse.ok(triggerService.replay(currentTenantId(), id));
    }
}
