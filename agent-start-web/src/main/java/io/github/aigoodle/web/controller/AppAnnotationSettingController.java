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

/** REST endpoints for an application's annotation-retrieval settings. */
@RestController
@ConditionalOnBean(AppAnnotationSettingService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/annotation-settings")
public class AppAnnotationSettingController {

    private final AppAnnotationSettingService settingService;

    public AppAnnotationSettingController(AppAnnotationSettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping
    public ApiResponse<AppAnnotationSettingEntity> get(@PathVariable String appId) {
        return ApiResponse.ok(settingService.getByApp(appId));
    }

    @PutMapping
    public ApiResponse<AppAnnotationSettingEntity> save(
            @PathVariable String appId,
            @RequestBody AppAnnotationSettingEntity updates) {
        return ApiResponse.ok(settingService.save(appId, updates));
    }

    @PostMapping("/status")
    public ApiResponse<AppAnnotationSettingEntity> setStatus(
            @PathVariable String appId,
            @RequestBody Map<String, Boolean> status) {
        boolean enabled = Boolean.TRUE.equals(status.get("enabled"));
        return ApiResponse.ok(settingService.setEnabled(appId, enabled));
    }
}
