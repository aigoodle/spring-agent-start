package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.service.AppMetricsService;
import io.github.aigoodle.agent.service.AppService;
import io.github.aigoodle.agent.service.AppMetricsView;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

/** REST endpoint for conversation activity derived from an application's messages. */
@RestController
@ConditionalOnBean(AppMetricsService.class)
@RequestMapping("/apps/{appId}/metrics")
public class AppMetricsController {

    private final AppMetricsService metricsService;
    private final AppService apps;

    public AppMetricsController(AppMetricsService metricsService, AppService apps) {
        this.metricsService = metricsService; this.apps = apps;
    }

    @GetMapping
    public ApiResponse<AppMetricsView> metrics(@PathVariable String appId) {
        apps.require(currentTenantId(), appId);
        return ApiResponse.ok(metricsService.summarize(currentTenantId(), appId));
    }
}
