package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.AppAnnotationSettingEntity;
import io.github.aigoodle.agent.service.AppAnnotationSettingService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manage the singleton annotation-retrieval config row per app (Dify parity —
 * "标注设置" panel). GET returns a fresh defaulted row when nothing has been
 * saved yet so the form renders on first open.
 */
@RestController
@ConditionalOnBean(AppAnnotationSettingService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/annotation-settings")
public class AppAnnotationSettingController {

    private final AppAnnotationSettingService service;

    public AppAnnotationSettingController(AppAnnotationSettingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AppAnnotationSettingEntity> get(@PathVariable String appId) {
        return ApiResponse.ok(service.getByApp(appId));
    }

    @PutMapping
    public ApiResponse<AppAnnotationSettingEntity> save(@PathVariable String appId,
                                                        @RequestBody AppAnnotationSettingEntity body) {
        return ApiResponse.ok(service.save(appId, body));
    }

    @PostMapping("/status")
    public ApiResponse<AppAnnotationSettingEntity> setStatus(@PathVariable String appId,
                                                              @RequestBody Map<String, Boolean> body) {
        return ApiResponse.ok(service.setEnabled(appId, Boolean.TRUE.equals(body.get("enabled"))));
    }
}
