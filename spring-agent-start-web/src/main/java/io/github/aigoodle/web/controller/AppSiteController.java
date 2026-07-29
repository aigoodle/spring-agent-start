package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.AppSiteEntity;
import io.github.aigoodle.agent.service.AppSiteService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Published-widget config for an app (Dify parity — the "网站" panel). One
 * row per app; auto-generated on first save. GET returns a fresh defaulted
 * row when nothing has been saved yet.
 */
@RestController
@ConditionalOnBean(AppSiteService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/site")
public class AppSiteController {

    private final AppSiteService service;

    public AppSiteController(AppSiteService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AppSiteEntity> get(@PathVariable String appId) {
        return ApiResponse.ok(service.getByApp(appId));
    }

    @PutMapping
    public ApiResponse<AppSiteEntity> save(@PathVariable String appId, @RequestBody AppSiteEntity body) {
        return ApiResponse.ok(service.save(appId, body));
    }
}
