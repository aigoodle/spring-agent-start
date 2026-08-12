package io.github.aigoodle.observability.agentops;

import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.runtime.AgentRunObservation;
import io.github.aigoodle.agent.runtime.AgentRunStatus;
import io.github.aigoodle.tool.execution.ToolExecutionContext;
import io.github.aigoodle.tool.execution.ToolExecutionRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicInteger;

class AgentOpsMetricsTest {

    @Test
    void recordsAgentLifecycleWithoutHighCardinalityRunTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var observer = new MicrometerAgentRunObserver(registry);
        var observation = new AgentRunObservation("run-secret", "tenant", "agent-1", "conversation",
                AgentStrategyType.REACT, false, AgentRunStatus.COMPLETED,
                Instant.now(), Duration.ofMillis(25), null);

        observer.onStarted(observation);
        observer.onFinished(observation);

        assertThat(registry.get("spring.agent.runs.started").counter().count()).isEqualTo(1);
        assertThat(registry.get("spring.agent.runs.finished").tag("status", "completed")
                .counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTag("run_id")).isNull());
    }

    @Test
    void recordsGovernedToolStatusDurationAndAttempts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var listener = new MicrometerToolExecutionListener(registry);
        listener.onExecution(new ToolExecutionRecord("run-1", "search",
                ToolExecutionRecord.Status.SUCCEEDED, 2, Duration.ofMillis(12), null,
                new ToolExecutionContext("run-1", "default", "agent-1", "c1", Map.of())));

        assertThat(registry.get("spring.agent.tools.executions").tag("tool", "search")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("spring.agent.tools.attempts").summary().totalAmount()).isEqualTo(2);
    }

    @Test
    void exposesRunAsObservationForOpenTelemetryBridges() {
        ObservationRegistry registry = ObservationRegistry.create();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public void onStart(Observation.Context context) { starts.incrementAndGet(); }
            public void onStop(Observation.Context context) { stops.incrementAndGet(); }
            public boolean supportsContext(Observation.Context context) { return true; }
        });
        var observer = new ObservationAgentRunObserver(registry);
        var signal = new AgentRunObservation("run-1", "tenant", "agent-1", "conversation",
                AgentStrategyType.REACT, false, AgentRunStatus.COMPLETED,
                Instant.now(), Duration.ofMillis(4), null);

        observer.onStarted(signal);
        assertThat(registry.getCurrentObservation()).isNotNull();
        observer.onFinished(signal);

        assertThat(starts).hasValue(1);
        assertThat(stops).hasValue(1);
        assertThat(registry.getCurrentObservation()).isNull();
    }
}
