package io.github.aigoodle.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.observability.api.LlmCallMeasurement;
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

    private static final Logger logger = LoggerFactory.getLogger(LlmMetricsService.class);

    private final LlmCallRecordMapper callRecordMapper;
    private final ObservabilityProperties observabilityProperties;
    private final LlmCallRecordFactory recordFactory;
    private final LlmUsageAggregator usageAggregator = new LlmUsageAggregator();

    public LlmMetricsService(LlmCallRecordMapper callRecordMapper,
                             ObservabilityProperties observabilityProperties) {
        this.callRecordMapper = callRecordMapper;
        this.observabilityProperties = observabilityProperties;
        this.recordFactory = new LlmCallRecordFactory(this::costMicros);
    }

    public LlmCallRecord record(LlmCallMeasurement measurement) {
        LlmCallRecord record = recordFactory.create(measurement);
        persistWithoutDisruptingModelCall(record);
        return record;
    }

    /** @deprecated use {@link #record(LlmCallMeasurement)} to avoid positional parameter mistakes. */
    @Deprecated
    public LlmCallRecord record(String provider, String model, String tenantId, TokenUsage usage,
                                long latencyMs, boolean success, String errorType) {
        return record(new LlmCallMeasurement(
                provider, model, tenantId, usage, latencyMs, success, errorType));
    }

    private void persistWithoutDisruptingModelCall(LlmCallRecord record) {
        try {
            callRecordMapper.insert(record);
        } catch (RuntimeException persistenceFailure) {
            // metering must never break the actual model call
            logger.warn("Failed to persist LLM call record: {}", persistenceFailure.getMessage());
        }
    }

    /** Cost in micro-currency units, from the configured pricing table (0 if unknown). */
    public long costMicros(String model, TokenUsage usage) {
        ObservabilityProperties.ModelPrice price = observabilityProperties.getPricing().get(model);
        if (price == null || usage == null) {
            return 0L;
        }
        double cost = usage.promptTokens() / 1000.0 * price.getInputPer1k()
                + usage.completionTokens() / 1000.0 * price.getOutputPer1k();
        return Math.round(cost * 1_000_000.0);
    }

    public List<LlmUsageStats> statsByModel(String tenantId) {
        Map<String, List<LlmCallRecord>> byModel = new LinkedHashMap<>();
        for (LlmCallRecord record : load(tenantId)) {
            byModel.computeIfAbsent(record.getModel(), ignored -> new ArrayList<>()).add(record);
        }
        return byModel.entrySet().stream()
                .map(entry -> usageAggregator.aggregate(entry.getKey(), entry.getValue()))
                .toList();
    }

    public LlmUsageStats total(String tenantId) {
        return usageAggregator.aggregate("*", load(tenantId));
    }

    public List<LlmCallRecord> recentCalls(int limit) {
        return callRecordMapper.selectList(new LambdaQueryWrapper<LlmCallRecord>()
                .orderByDesc(LlmCallRecord::getCreatedAt)
                .last("limit " + Math.max(1, limit)));
    }

    private List<LlmCallRecord> load(String tenantId) {
        LambdaQueryWrapper<LlmCallRecord> query = new LambdaQueryWrapper<>();
        if (tenantId != null) {
            query.eq(LlmCallRecord::getTenantId, tenantId);
        }
        return callRecordMapper.selectList(query);
    }
}
