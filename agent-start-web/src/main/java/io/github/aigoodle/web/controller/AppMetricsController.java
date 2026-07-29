package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.service.AppMetricsService;
import io.github.aigoodle.agent.service.AppMetricsView;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Powers the "监测" tab in the app design drawer — per-app conversation +
 * message counts derived from the {@code messages} table. Global LLM stats
 * (tokens / cost / recent calls) still come from
 * {@link ObservabilityController} because {@code llm_calls} carries no
 * {@code app_id} column yet.
 */
@RestController
@ConditionalOnBean(AppMetricsService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/metrics")
public class AppMetricsController {

    private final AppMetricsService service;

    public AppMetricsController(AppMetricsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AppMetricsView> metrics(@PathVariable String appId) {
        return ApiResponse.ok(service.compute(appId));
    }
}
