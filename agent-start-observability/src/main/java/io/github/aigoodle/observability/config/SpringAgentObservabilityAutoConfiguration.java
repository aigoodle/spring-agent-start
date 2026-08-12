package io.github.aigoodle.observability.config;

import io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration;
import io.github.aigoodle.model.runtime.ChatModelDecorator;
import io.github.aigoodle.observability.mapper.LlmCallRecordMapper;
import io.github.aigoodle.observability.metering.MeteringChatModelDecorator;
import io.github.aigoodle.observability.service.LlmMetricsService;
import io.github.aigoodle.agent.runtime.AgentRunObserver;
import io.github.aigoodle.tool.execution.ToolExecutionListener;
import io.github.aigoodle.observability.agentops.MicrometerAgentRunObserver;
import io.github.aigoodle.observability.agentops.MicrometerToolExecutionListener;
import io.github.aigoodle.observability.agentops.ObservationAgentRunObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for LLMOps: the metrics service plus the metering decorator that
 * the model module applies to every chat model. Disable with
 * {@code spring-agent.observability.enabled=false}.
 */
@AutoConfiguration(before = SpringAgentModelAutoConfiguration.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
@MapperScan("io.github.aigoodle.observability.mapper")
public class SpringAgentObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LlmMetricsService llmMetricsService(LlmCallRecordMapper callRecordMapper,
                                               ObservabilityProperties observabilityProperties) {
        return new LlmMetricsService(callRecordMapper, observabilityProperties);
    }

    @Bean
    @ConditionalOnMissingBean(name = "meteringChatModelDecorator")
    @ConditionalOnProperty(prefix = "spring-agent.observability", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ChatModelDecorator meteringChatModelDecorator(LlmMetricsService metricsService) {
        return new MeteringChatModelDecorator(metricsService);
    }

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "spring-agent.observability", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentRunObserver micrometerAgentRunObserver(MeterRegistry registry) {
        return new MicrometerAgentRunObserver(registry);
    }

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "spring-agent.observability", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ToolExecutionListener micrometerToolExecutionListener(MeterRegistry registry) {
        return new MicrometerToolExecutionListener(registry);
    }

    @Bean
    @ConditionalOnClass(ObservationRegistry.class)
    @ConditionalOnBean(ObservationRegistry.class)
    @ConditionalOnProperty(prefix = "spring-agent.observability", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentRunObserver observationAgentRunObserver(ObservationRegistry registry) {
        return new ObservationAgentRunObserver(registry);
    }
}
