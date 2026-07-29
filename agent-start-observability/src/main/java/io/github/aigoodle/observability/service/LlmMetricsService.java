package io.github.aigoodle.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.observability.api.LlmUsageStats;
import io.github.aigoodle.observability.api.TokenUsage;
import io.github.aigoodle.observability.config.ObservabilityProperties;
import io.github.aigoodle.observability.entity.LlmCallRecord;
import io.github.aigoodle.observability.mapper.LlmCallRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records every LLM call (tokens, cost, latency, success) and answers the aggregate
 * questions an LLMOps view needs: spend and volume per model, error rate, latency.
 */
public class LlmMetricsService {

    private static final Logger log = LoggerFactory.getLogger(LlmMetricsService.class);

    private final LlmCallRecordMapper mapper;
    private final ObservabilityProperties properties;

    public LlmMetricsService(LlmCallRecordMapper mapper, ObservabilityProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public LlmCallRecord record(String provider, String model, String tenantId, TokenUsage usage,
                                long latencyMs, boolean success, String errorType) {
        TokenUsage u = usage == null ? TokenUsage.ZERO : usage;
        LlmCallRecord rec = new LlmCallRecord();
        rec.setTenantId(tenantId);
        rec.setProvider(provider);
        rec.setModel(model);
        rec.setPromptTokens(u.promptTokens());
        rec.setCompletionTokens(u.completionTokens());
        rec.setTotalTokens(u.totalTokens());
        rec.setCostMicros(costMicros(model, u));
        rec.setLatencyMs(latencyMs);
        rec.setSuccess(success);
        rec.setErrorType(errorType);
        try {
            mapper.insert(rec);
        } catch (Exception e) {
            // metering must never break the actual model call
            log.warn("Failed to persist LLM call record: {}", e.getMessage());
        }
        return rec;
    }

    /** Cost in micro-currency units, from the configured pricing table (0 if unknown). */
    public long costMicros(String model, TokenUsage usage) {
        ObservabilityProperties.ModelPrice price = properties.getPricing().get(model);
        if (price == null || usage == null) {
            return 0L;
        }
        double cost = usage.promptTokens() / 1000.0 * price.getInputPer1k()
                + usage.completionTokens() / 1000.0 * price.getOutputPer1k();
        return Math.round(cost * 1_000_000.0);
    }

    public List<LlmUsageStats> statsByModel(String tenantId) {
        Map<String, List<LlmCallRecord>> byModel = new LinkedHashMap<>();
        for (LlmCallRecord r : load(tenantId)) {
            byModel.computeIfAbsent(r.getModel(), k -> new ArrayList<>()).add(r);
        }
        List<LlmUsageStats> stats = new ArrayList<>();
        byModel.forEach((model, records) -> stats.add(aggregate(model, records)));
        return stats;
    }

    public LlmUsageStats total(String tenantId) {
        return aggregate("*", load(tenantId));
    }

    public List<LlmCallRecord> recentCalls(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<LlmCallRecord>()
                .orderByDesc(LlmCallRecord::getCreatedAt)
                .last("limit " + Math.max(1, limit)));
    }

    private List<LlmCallRecord> load(String tenantId) {
        LambdaQueryWrapper<LlmCallRecord> q = new LambdaQueryWrapper<>();
        if (tenantId != null) {
            q.eq(LlmCallRecord::getTenantId, tenantId);
        }
        return mapper.selectList(q);
    }

    private LlmUsageStats aggregate(String model, List<LlmCallRecord> records) {
        long calls = records.size();
        long errors = 0, prompt = 0, completion = 0, total = 0, cost = 0, latency = 0;
        for (LlmCallRecord r : records) {
            if (!Boolean.TRUE.equals(r.getSuccess())) {
                errors++;
            }
            prompt += nz(r.getPromptTokens());
            completion += nz(r.getCompletionTokens());
            total += nz(r.getTotalTokens());
            cost += r.getCostMicros() == null ? 0 : r.getCostMicros();
            latency += r.getLatencyMs() == null ? 0 : r.getLatencyMs();
        }
        return LlmUsageStats.builder()
                .model(model).calls(calls).errors(errors)
                .promptTokens(prompt).completionTokens(completion).totalTokens(total)
                .costMicros(cost)
                .avgLatencyMs(calls == 0 ? 0.0 : (double) latency / calls)
                .build();
    }

    private static long nz(Integer i) {
        return i == null ? 0 : i;
    }
}
