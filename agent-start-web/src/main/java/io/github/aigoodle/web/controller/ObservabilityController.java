package io.github.aigoodle.web.controller;

import io.github.aigoodle.observability.api.LlmUsageStats;
import io.github.aigoodle.observability.entity.LlmCallRecord;
import io.github.aigoodle.observability.service.LlmMetricsService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST facade over {@link LlmMetricsService}. Exposes the numbers an LLMOps view
 * needs: per-model aggregates, a running total and the most recent raw calls.
 * <p>
 * Only wired when the observability module is on the classpath.
 */
@RestController
@ConditionalOnBean(LlmMetricsService.class)
@RequestMapping("${spring-agent.web.base-path:}/llmops")
public class ObservabilityController {

    private final LlmMetricsService metrics;

    public ObservabilityController(LlmMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/stats")
    public ApiResponse<List<LlmUsageStats>> statsByModel(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(metrics.statsByModel(tenantId));
    }

    @GetMapping("/total")
    public ApiResponse<LlmUsageStats> total(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(metrics.total(tenantId));
    }

    @GetMapping("/recent")
    public ApiResponse<List<LlmCallRecord>> recent(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(metrics.recentCalls(limit));
    }
}
