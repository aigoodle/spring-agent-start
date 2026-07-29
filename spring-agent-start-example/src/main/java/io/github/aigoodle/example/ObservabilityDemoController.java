package io.github.aigoodle.example;

import io.github.aigoodle.observability.api.LlmUsageStats;
import io.github.aigoodle.observability.entity.LlmCallRecord;
import io.github.aigoodle.observability.service.LlmMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates LLMOps: token/cost/latency aggregates collected automatically from every
 * model call by the observability module.
 */
@RestController
public class ObservabilityDemoController {

    private final LlmMetricsService metrics;

    public ObservabilityDemoController(LlmMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/llmops/stats")
    public List<LlmUsageStats> statsByModel(@RequestParam(required = false) String tenantId) {
        return metrics.statsByModel(tenantId);
    }

    @GetMapping("/llmops/total")
    public Map<String, Object> total(@RequestParam(required = false) String tenantId) {
        LlmUsageStats t = metrics.total(tenantId);
        return Map.of(
                "calls", t.getCalls(),
                "errors", t.getErrors(),
                "totalTokens", t.getTotalTokens(),
                "costMicros", t.getCostMicros(),
                "costAmount", t.costAmount(),
                "avgLatencyMs", t.getAvgLatencyMs());
    }

    @GetMapping("/llmops/recent")
    public List<LlmCallRecord> recent(@RequestParam(defaultValue = "20") int limit) {
        return metrics.recentCalls(limit);
    }
}
