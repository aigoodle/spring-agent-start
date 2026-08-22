package io.github.aigoodle.web.controller;

import io.github.aigoodle.observability.api.LlmUsageStats;
import io.github.aigoodle.observability.api.LlmTrendPoint;
import io.github.aigoodle.observability.api.LlmTrendRange;
import io.github.aigoodle.observability.entity.LlmCallRecord;
import io.github.aigoodle.observability.service.LlmMetricsService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

/**
 * REST facade over {@link LlmMetricsService}. Exposes the numbers an LLMOps view
 * needs: per-model aggregates, a running total and the most recent raw calls.
 * <p>
 * Only wired when the observability module is on the classpath.
 */
@RestController
@ConditionalOnBean(LlmMetricsService.class)
@RequestMapping("/llmops")
public class ObservabilityController {

    private final LlmMetricsService metrics;

    public ObservabilityController(LlmMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/stats")
    public ApiResponse<List<LlmUsageStats>> statsByModel() {
        return ApiResponse.ok(metrics.statsByModel(currentTenantId()));
    }

    @GetMapping("/total")
    public ApiResponse<LlmUsageStats> total() {
        return ApiResponse.ok(metrics.total(currentTenantId()));
    }

    @GetMapping("/recent")
    public ApiResponse<List<LlmCallRecord>> recent(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(metrics.recentCalls(limit));
    }

    @GetMapping("/trend")
    public ApiResponse<List<LlmTrendPoint>> trend(
            @RequestParam(defaultValue = "HOUR") LlmTrendRange range) {
        return ApiResponse.ok(metrics.trend(currentTenantId(), range));
    }
}
