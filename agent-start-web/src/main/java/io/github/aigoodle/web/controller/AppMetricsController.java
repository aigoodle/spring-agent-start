package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.service.AppMetricsService;
import io.github.aigoodle.agent.service.AppMetricsView;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint for conversation activity derived from an application's messages. */
@RestController
@ConditionalOnBean(AppMetricsService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/metrics")
public class AppMetricsController {

    private final AppMetricsService metricsService;

    public AppMetricsController(AppMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping
    public ApiResponse<AppMetricsView> metrics(@PathVariable String appId) {
        return ApiResponse.ok(metricsService.summarize(appId));
    }
}
