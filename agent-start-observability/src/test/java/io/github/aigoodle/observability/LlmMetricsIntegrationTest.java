package io.github.aigoodle.observability;

import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.service.ModelRegistration;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.observability.api.LlmUsageStats;
import io.github.aigoodle.observability.api.TokenUsage;
import io.github.aigoodle.observability.entity.LlmCallRecord;
import io.github.aigoodle.observability.service.LlmMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ObservabilityTestApplication.class)
class LlmMetricsIntegrationTest {

    @Autowired
    private ModelService modelService;
    @Autowired
    private LlmMetricsService metrics;

    @Test
    void chatCallIsMeteredEndToEnd() {
        ModelEntity model = modelService.register(ModelRegistration.builder()
                .tenantId("obs").providerName("scripted").modelName("gpt-4o-mini")
                .modelType(ModelType.LLM).build());

        // a real chat call through ModelService -> the metering decorator records it
        String reply = modelService.getChatClient(model.getId()).prompt().user("hello").call().content();
        assertEquals("ok", reply);

        LlmCallRecord rec = metrics.recentCalls(50).stream()
                .filter(r -> "gpt-4o-mini".equals(r.getModel()))
                .findFirst().orElseThrow();
        assertEquals(20, rec.getTotalTokens());
        assertEquals(12, rec.getPromptTokens());
        assertEquals(8, rec.getCompletionTokens());
        assertTrue(rec.getSuccess());
        assertNotNull(rec.getCostMicros());
        assertEquals(metrics.costMicros("gpt-4o-mini", new TokenUsage(12, 8, 20)), rec.getCostMicros());

        LlmUsageStats stats = metrics.statsByModel(null).stream()
                .filter(s -> "gpt-4o-mini".equals(s.getModel())).findFirst().orElseThrow();
        assertTrue(stats.getCalls() >= 1);
        assertTrue(stats.getTotalTokens() >= 20);
    }

    @Test
    void recordsAggregateByTenant() {
        metrics.record("openai", "gpt-4o", "tenantA", new TokenUsage(100, 50, 150), 200, true, null);
        metrics.record("openai", "gpt-4o", "tenantA", new TokenUsage(100, 50, 150), 400, false, "Timeout");

        LlmUsageStats total = metrics.total("tenantA");
        assertEquals(2, total.getCalls());
        assertEquals(1, total.getErrors());
        assertEquals(300, total.getTotalTokens());
        assertEquals(300.0, total.getAvgLatencyMs());
        // gpt-4o priced: prompt 200/1k*0.0025 + completion 100/1k*0.01 = 0.0005 + 0.001 = $0.0015 -> 1500 micros
        assertEquals(1500L, total.getCostMicros());
    }

    @Test
    void directRecordAggregates() {
        metrics.record("ollama", "llama3.2", "tenantB", TokenUsage.of(10, 5, null), 50, true, null);
        assertEquals(15, metrics.total("tenantB").getTotalTokens());
    }
}
