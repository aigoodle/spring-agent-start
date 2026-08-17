package io.github.aigoodle.agent.config;

import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.agent.context.AgentContextEngine;
import io.github.aigoodle.agent.context.DefaultAgentContextEngine;
import io.github.aigoodle.agent.context.AgentContextCompactor;
import io.github.aigoodle.agent.context.ExtractiveAgentContextCompactor;
import io.github.aigoodle.agent.hitl.AutoApproveGate;
import io.github.aigoodle.agent.mapper.AppMapper;
import io.github.aigoodle.agent.mapper.AgentRunEventMapper;
import io.github.aigoodle.agent.mapper.AgentRunMapper;
import io.github.aigoodle.agent.mapper.AppApiTokenMapper;
import io.github.aigoodle.agent.mapper.AppAnnotationMapper;
import io.github.aigoodle.agent.mapper.AppAnnotationSettingMapper;
import io.github.aigoodle.agent.mapper.AppModelConfigMapper;
import io.github.aigoodle.agent.mapper.AppSiteMapper;
import io.github.aigoodle.agent.mapper.AppConversationMapper;
import io.github.aigoodle.agent.mapper.TagBindingMapper;
import io.github.aigoodle.agent.mapper.TagMapper;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.AppService;
import io.github.aigoodle.agent.service.AppApiTokenService;
import io.github.aigoodle.agent.service.AppAnnotationService;
import io.github.aigoodle.agent.service.AppAnnotationSettingService;
import io.github.aigoodle.agent.service.AppDatasetService;
import io.github.aigoodle.agent.service.AppMetricsService;
import io.github.aigoodle.agent.service.AppModelConfigService;
import io.github.aigoodle.agent.service.AppSiteService;
import io.github.aigoodle.agent.service.AppConversationService;
import io.github.aigoodle.agent.service.TagService;
import io.github.aigoodle.agent.runtime.AgentRunStore;
import io.github.aigoodle.agent.runtime.JdbcAgentRunStore;
import io.github.aigoodle.agent.runtime.AgentRunToolExecutionListener;
import io.github.aigoodle.agent.runtime.AgentRunObserver;
import io.github.aigoodle.agent.strategy.AgentStrategy;
import io.github.aigoodle.agent.strategy.AgentStrategyRegistry;
import io.github.aigoodle.agent.strategy.FunctionCallingStrategy;
import io.github.aigoodle.agent.strategy.PlanExecuteStrategy;
import io.github.aigoodle.agent.strategy.ReActStrategy;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.model.config.GoodleModelAutoConfiguration;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.memory.MemoryManager;
import io.github.aigoodle.memory.config.GoodleMemoryAutoConfiguration;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.tool.execution.ToolExecutionGateway;
import io.github.aigoodle.tool.execution.ToolExecutionListener;
import io.github.aigoodle.tool.config.GoodleToolsAutoConfiguration;
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
@AutoConfiguration(after = {GoodleModelAutoConfiguration.class, GoodleToolsAutoConfiguration.class,
        GoodleMemoryAutoConfiguration.class})
@EnableConfigurationProperties(AgentProperties.class)
@MapperScan("io.github.aigoodle.agent.mapper")
public class AgentRuntimeAutoConfiguration {

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
    public AgentRunStore agentRunStore(AgentRunMapper runMapper, AgentRunEventMapper eventMapper) {
        return new JdbcAgentRunStore(runMapper, eventMapper);
    }

    @Bean
    public ToolExecutionListener agentRunToolExecutionListener(AgentRunStore runStore) {
        return new AgentRunToolExecutionListener(runStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentContextCompactor agentContextCompactor() {
        return new ExtractiveAgentContextCompactor();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentContextEngine agentContextEngine(MemoryManager memory, AgentProperties properties,
                                                 AgentContextCompactor compactor) {
        return new DefaultAgentContextEngine(memory, properties, compactor);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentService agentService(AppMapper appMapper, AppModelConfigService modelConfigService,
                                     ModelService modelService, ToolRegistry toolRegistry,
                                     AgentStrategyRegistry strategyRegistry,
                                     MemoryManager memory, ApprovalGate approvalGate,
                                     AgentRunStore runStore,
                                     ToolExecutionGateway toolExecutionGateway,
                                     AgentContextEngine contextEngine,
                                     List<AgentRunObserver> runObservers) {
        return new AgentService(appMapper, modelConfigService, modelService, toolRegistry,
                strategyRegistry, memory, approvalGate, runStore, toolExecutionGateway,
                contextEngine, runObservers);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppService appService(AgentService agentService) {
        return new AppService(agentService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppAnnotationService appAnnotationService(AppAnnotationMapper mapper) {
        return new AppAnnotationService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppMetricsService appMetricsService(MemoryManager memoryManager, AppMapper appMapper) {
        return new AppMetricsService(memoryManager, appMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppConversationService appConversationService(AppConversationMapper conversationMapper,
                                                          MemoryManager memoryManager) {
        return new AppConversationService(conversationMapper, memoryManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppApiTokenService appApiTokenService(AppApiTokenMapper mapper) {
        return new AppApiTokenService(mapper);
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
        public AppDatasetService appDatasetService(AppMapper appMapper,
                                                    AppModelConfigService modelConfigService,
                                                    DatasetService datasetService) {
            return new AppDatasetService(appMapper, modelConfigService, datasetService);
        }
    }
}
