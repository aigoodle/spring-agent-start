package io.github.aigoodle.agent.config;

import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.agent.hitl.AutoApproveGate;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;
import io.github.aigoodle.agent.mapper.ApiTokenMapper;
import io.github.aigoodle.agent.mapper.AppAnnotationMapper;
import io.github.aigoodle.agent.mapper.AppAnnotationSettingMapper;
import io.github.aigoodle.agent.mapper.AppModelConfigMapper;
import io.github.aigoodle.agent.mapper.AppSiteMapper;
import io.github.aigoodle.agent.mapper.ConversationMapper;
import io.github.aigoodle.agent.mapper.TagBindingMapper;
import io.github.aigoodle.agent.mapper.TagMapper;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.memory.JdbcAgentMemory;
import io.github.aigoodle.agent.memory.VectorAgentMemory;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.ApiTokenService;
import io.github.aigoodle.agent.service.AppAnnotationService;
import io.github.aigoodle.agent.service.AppAnnotationSettingService;
import io.github.aigoodle.agent.service.AppDatasetService;
import io.github.aigoodle.agent.service.AppMetricsService;
import io.github.aigoodle.agent.service.AppModelConfigService;
import io.github.aigoodle.agent.service.AppSiteService;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.agent.service.TagService;
import io.github.aigoodle.agent.strategy.AgentStrategy;
import io.github.aigoodle.agent.strategy.AgentStrategyRegistry;
import io.github.aigoodle.agent.strategy.FunctionCallingStrategy;
import io.github.aigoodle.agent.strategy.PlanExecuteStrategy;
import io.github.aigoodle.agent.strategy.ReActStrategy;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.tool.config.SpringAgentToolsAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Auto-configuration for the enterprise agent runtime: strategies, memory, the
 * approval gate and the {@link AgentService}.
 */
@AutoConfiguration(after = {SpringAgentModelAutoConfiguration.class, SpringAgentToolsAutoConfiguration.class})
@EnableConfigurationProperties(AgentProperties.class)
@MapperScan("io.github.aigoodle.agent.mapper")
public class SpringAgentAgentAutoConfiguration {

    @Bean
    public ReActStrategy reActStrategy() {
        return new ReActStrategy();
    }

    @Bean
    public FunctionCallingStrategy functionCallingStrategy() {
        return new FunctionCallingStrategy();
    }

    @Bean
    public PlanExecuteStrategy planExecuteStrategy() {
        return new PlanExecuteStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentStrategyRegistry agentStrategyRegistry(List<AgentStrategy> strategies) {
        return new AgentStrategyRegistry(strategies);
    }

    @Bean
    @ConditionalOnMissingBean(AgentMemory.class)
    @ConditionalOnProperty(prefix = "spring-agent.agent", name = "memory", havingValue = "jdbc", matchIfMissing = true)
    public AgentMemory agentMemory(AgentMessageMapper mapper) {
        return new JdbcAgentMemory(mapper);
    }

    /** Semantic long-term memory, active with {@code spring-agent.agent.memory=vector} and the knowledge module. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KnowledgeService.class)
    @ConditionalOnProperty(prefix = "spring-agent.agent", name = "memory", havingValue = "vector")
    static class VectorMemoryConfiguration {
        @Bean
        @ConditionalOnMissingBean(AgentMemory.class)
        @ConditionalOnBean({KnowledgeService.class, DatasetService.class})
        public AgentMemory vectorAgentMemory(KnowledgeService knowledgeService, DatasetService datasetService,
                                              AgentMessageMapper messageMapper, AgentProperties properties) {
            return new VectorAgentMemory(knowledgeService, datasetService, properties.getEmbeddingModelId(),
                    properties.getMemoryDatasetName(), new JdbcAgentMemory(messageMapper));
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalGate approvalGate() {
        return new AutoApproveGate();
    }

    @Bean
    @ConditionalOnMissingBean
    public AppModelConfigService appModelConfigService(AppModelConfigMapper mapper) {
        return new AppModelConfigService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentService agentService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                                     ModelService modelService, ToolRegistry toolRegistry,
                                     AgentStrategyRegistry strategyRegistry,
                                     AgentMemory memory, ApprovalGate approvalGate) {
        return new AgentService(agentMapper, modelConfigService, modelService, toolRegistry,
                strategyRegistry, memory, approvalGate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppAnnotationService appAnnotationService(AppAnnotationMapper mapper) {
        return new AppAnnotationService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppMetricsService appMetricsService(AgentMessageMapper messageMapper) {
        return new AppMetricsService(messageMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationService conversationService(ConversationMapper conversationMapper,
                                                    AgentMessageMapper messageMapper) {
        return new ConversationService(conversationMapper, messageMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiTokenService apiTokenService(ApiTokenMapper mapper) {
        return new ApiTokenService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppAnnotationSettingService appAnnotationSettingService(AppAnnotationSettingMapper mapper) {
        return new AppAnnotationSettingService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppSiteService appSiteService(AppSiteMapper mapper) {
        return new AppSiteService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TagService tagService(TagMapper tagMapper, TagBindingMapper bindingMapper) {
        return new TagService(tagMapper, bindingMapper);
    }

    /**
     * Knowledge-base attachment on apps. Wired only when the knowledge module
     * is on the classpath — the agent module stays independent otherwise.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DatasetService.class)
    static class AppDatasetConfiguration {
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(DatasetService.class)
        public AppDatasetService appDatasetService(AgentMapper agentMapper,
                                                    AppModelConfigService modelConfigService,
                                                    DatasetService datasetService) {
            return new AppDatasetService(agentMapper, modelConfigService, datasetService);
        }
    }
}
