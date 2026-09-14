package io.github.aigoodle.workflow.config;

import io.github.aigoodle.common.trigger.ScheduledTaskGateway;
import io.github.aigoodle.knowledge.reader.DocumentExtractor;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.model.config.GoodleModelAutoConfiguration;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.PromptTemplateService;
import io.github.aigoodle.workflow.engine.NodeExecutorRegistry;
import io.github.aigoodle.workflow.engine.WorkflowEngine;
import io.github.aigoodle.workflow.mapper.WorkflowMapper;
import io.github.aigoodle.workflow.mapper.WorkflowRunMapper;
import io.github.aigoodle.workflow.mapper.WorkflowCheckpointMapper;
import io.github.aigoodle.workflow.mapper.WorkflowExecutionEventMapper;
import io.github.aigoodle.workflow.mapper.WorkflowRunNodeMapper;
import io.github.aigoodle.workflow.mapper.WorkflowResumeSignalMapper;
import io.github.aigoodle.workflow.mapper.HumanInteractionMapper;
import io.github.aigoodle.memory.MemoryManager;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.builtin.AgentNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.AnswerNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.CodeNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.DocumentExtractorNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.EndNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.HttpRequestNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.IfElseNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.IterationNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.KnowledgeRetrievalNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.ListOperatorNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.LlmNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.ParameterExtractorNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.QuestionClassifierNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.ServiceApiNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.ScheduleTriggerNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.StartNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.TemplateTransformNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.VariableAggregatorNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.VariableAssignerNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.HumanInputNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.ApprovalNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.WaitEventNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.SleepUntilNodeExecutor;
import io.github.aigoodle.workflow.service.WorkflowService;
import io.github.aigoodle.workflow.service.WorkflowCheckpointStore;
import io.github.aigoodle.workflow.service.PersistentWorkflowRunner;
import io.github.aigoodle.workflow.service.HumanInteractionStore;
import io.github.aigoodle.workflow.service.HumanInteractionService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * Auto-configuration for the workflow module. Registers built-in node executors, the
 * DAG engine and the workflow service. Nodes that need optional modules
 * (knowledge / tools / JEXL) are only wired when those modules are on the classpath.
 */
@AutoConfiguration(
        after = GoodleModelAutoConfiguration.class,
        afterName = "io.github.aigoodle.connector.config.GoodleConnectorAutoConfiguration")
@EnableConfigurationProperties(GoodleWorkflowProperties.class)
@EnableScheduling
@MapperScan("io.github.aigoodle.workflow.mapper")
public class GoodleWorkflowAutoConfiguration {

    // ---- executors with no extra dependencies ----

    @Bean
    public StartNodeExecutor startNodeExecutor() {
        return new StartNodeExecutor();
    }

    @Bean
    public EndNodeExecutor endNodeExecutor() {
        return new EndNodeExecutor();
    }

    @Bean
    public AnswerNodeExecutor answerNodeExecutor() {
        return new AnswerNodeExecutor();
    }

    @Bean
    public TemplateTransformNodeExecutor templateTransformNodeExecutor() {
        return new TemplateTransformNodeExecutor();
    }

    @Bean
    public VariableAggregatorNodeExecutor variableAggregatorNodeExecutor() {
        return new VariableAggregatorNodeExecutor();
    }

    @Bean
    public VariableAssignerNodeExecutor variableAssignerNodeExecutor() {
        return new VariableAssignerNodeExecutor();
    }

    @Bean
    public IfElseNodeExecutor ifElseNodeExecutor() {
        return new IfElseNodeExecutor();
    }

    @Bean public HumanInputNodeExecutor humanInputNodeExecutor(ModelService modelService) { return new HumanInputNodeExecutor(modelService); }
    @Bean public ApprovalNodeExecutor approvalNodeExecutor() { return new ApprovalNodeExecutor(); }
    @Bean public WaitEventNodeExecutor waitEventNodeExecutor() { return new WaitEventNodeExecutor(); }
    @Bean public SleepUntilNodeExecutor sleepUntilNodeExecutor() { return new SleepUntilNodeExecutor(); }

    @Bean
    public HttpRequestNodeExecutor httpRequestNodeExecutor(GoodleWorkflowProperties properties) {
        return new HttpRequestNodeExecutor(properties.getHttpBaseUrl());
    }

    @Bean
    public ServiceApiNodeExecutor serviceApiNodeExecutor(GoodleWorkflowProperties properties) {
        return new ServiceApiNodeExecutor(properties.getHttpBaseUrl());
    }

    @Bean
    public ListOperatorNodeExecutor listOperatorNodeExecutor() {
        return new ListOperatorNodeExecutor();
    }

    // ---- iteration: needs the engine but must not fetch it at bean-creation time ----

    @Bean
    public IterationNodeExecutor iterationNodeExecutor(ObjectProvider<WorkflowEngine> engine) {
        return new IterationNodeExecutor(engine::getObject);
    }

    // ---- executors needing the model module ----

    @Bean
    public LlmNodeExecutor llmNodeExecutor(ModelService modelService,
                                           ObjectProvider<PromptTemplateService> promptTemplateService,
                                           MemoryManager conversationMemory) {
        return new LlmNodeExecutor(
                modelService,
                promptTemplateService.getIfAvailable(),
                conversationMemory);
    }

    @Bean
    public QuestionClassifierNodeExecutor questionClassifierNodeExecutor(ModelService modelService,
                                                                          ObjectProvider<PromptTemplateService> promptTemplateService,
                                                                          MemoryManager memoryManager) {
        return new QuestionClassifierNodeExecutor(modelService, promptTemplateService.getIfAvailable(), memoryManager);
    }

    @Bean
    public ParameterExtractorNodeExecutor parameterExtractorNodeExecutor(ModelService modelService,
                                                                          MemoryManager memoryManager) {
        return new ParameterExtractorNodeExecutor(modelService, memoryManager);
    }

    @Bean
    public ScheduleTriggerNodeExecutor scheduleTriggerNodeExecutor(
            ObjectProvider<ScheduledTaskGateway> scheduledTaskGateway,
            ObjectProvider<ModelService> modelService) {
        return new ScheduleTriggerNodeExecutor(scheduledTaskGateway, modelService);
    }

    @Bean
    public AgentNodeExecutor agentNodeExecutor(io.github.aigoodle.agent.runtime.AgentRuntime agentRuntime,
                                               ModelService modelService) {
        return new AgentNodeExecutor(agentRuntime, modelService);
    }

    // ---- optional: knowledge-based nodes ----

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KnowledgeService.class)
    static class KnowledgeNodeConfiguration {
        @Bean
        @ConditionalOnBean(KnowledgeService.class)
        public KnowledgeRetrievalNodeExecutor knowledgeRetrievalNodeExecutor(KnowledgeService knowledgeService) {
            return new KnowledgeRetrievalNodeExecutor(knowledgeService);
        }

        @Bean
        @ConditionalOnBean(DocumentExtractor.class)
        public DocumentExtractorNodeExecutor documentExtractorNodeExecutor(DocumentExtractor documentExtractor) {
            return new DocumentExtractorNodeExecutor(documentExtractor);
        }
    }

    // ---- optional: tool node (needs the tools module) ----

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(io.github.aigoodle.tool.ToolRegistry.class)
    static class ToolNodeConfiguration {
        @Bean
        @ConditionalOnBean(io.github.aigoodle.tool.ToolRegistry.class)
        public io.github.aigoodle.workflow.node.builtin.ToolNodeExecutor toolNodeExecutor(
                io.github.aigoodle.tool.ToolRegistry toolRegistry,
                io.github.aigoodle.tool.execution.ToolExecutionGateway executionGateway) {
            return new io.github.aigoodle.workflow.node.builtin.ToolNodeExecutor(
                    toolRegistry, executionGateway);
        }
    }

    // ---- optional: provider-neutral connector node ----

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.github.aigoodle.connector.execution.ConnectorExecutionGateway")
    static class ConnectorNodeConfiguration {
        @Bean
        @ConditionalOnBean(type = "io.github.aigoodle.connector.execution.ConnectorExecutionGateway")
        public io.github.aigoodle.workflow.node.builtin.VideoGenerationNodeExecutor videoGenerationNodeExecutor(
                io.github.aigoodle.connector.execution.ConnectorExecutionGateway gateway) {
            return new io.github.aigoodle.workflow.node.builtin.VideoGenerationNodeExecutor(gateway);
        }
        @Bean
        @ConditionalOnBean(type = "io.github.aigoodle.connector.execution.ConnectorExecutionGateway")
        public io.github.aigoodle.workflow.node.builtin.ConnectorNodeExecutor connectorNodeExecutor(
                io.github.aigoodle.connector.execution.ConnectorExecutionGateway gateway,
                io.github.aigoodle.connector.channel.ChannelConnectionService channelConnections,
                io.github.aigoodle.connector.channel.ChannelRuntimeRegistry channelRuntimes) {
            return new io.github.aigoodle.workflow.node.builtin.ConnectorNodeExecutor(
                    gateway, channelConnections, channelRuntimes);
        }
    }

    // ---- optional: CODE node (needs Apache Commons JEXL 3 on the classpath) ----

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.commons.jexl3.JexlEngine")
    static class CodeNodeConfiguration {
        @Bean
        public CodeNodeExecutor codeNodeExecutor() {
            return new CodeNodeExecutor();
        }
    }

    // ---- engine + service ----

    @Bean
    @ConditionalOnMissingBean
    public WorkflowCheckpointStore workflowCheckpointStore(WorkflowCheckpointMapper checkpointMapper,
                                                            WorkflowRunNodeMapper nodeMapper,
                                                            WorkflowExecutionEventMapper eventMapper,
                                                            WorkflowResumeSignalMapper signalMapper) {
        return new WorkflowCheckpointStore(checkpointMapper, nodeMapper, eventMapper, signalMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public HumanInteractionStore humanInteractionStore(HumanInteractionMapper mapper,
            ObjectProvider<io.github.aigoodle.workflow.interaction.HumanInteractionNotifier> notifier) {
        return new HumanInteractionStore(mapper, notifier.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public PersistentWorkflowRunner persistentWorkflowRunner(WorkflowEngine engine,
                                                              WorkflowCheckpointStore checkpointStore,
                                                              HumanInteractionStore humanInteractions) {
        return new PersistentWorkflowRunner(engine, checkpointStore, humanInteractions);
    }

    @Bean
    @ConditionalOnMissingBean
    public HumanInteractionService humanInteractionService(HumanInteractionStore store,
                                                            PersistentWorkflowRunner runner,
                                                            ObjectProvider<MemoryManager> memory) {
        return new HumanInteractionService(store, runner, memory.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public io.github.aigoodle.workflow.service.WorkflowWaitRecoveryService workflowWaitRecoveryService(
            WorkflowCheckpointStore store, PersistentWorkflowRunner runner) {
        return new io.github.aigoodle.workflow.service.WorkflowWaitRecoveryService(store, runner);
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeExecutorRegistry nodeExecutorRegistry(List<NodeExecutor> executors) {
        return new NodeExecutorRegistry(executors);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowEngine workflowEngine(NodeExecutorRegistry registry) {
        return new WorkflowEngine(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowService workflowService(WorkflowMapper workflowMapper, WorkflowRunMapper runMapper,
                                           WorkflowEngine engine, PersistentWorkflowRunner persistentRunner,
                                           ObjectProvider<io.github.aigoodle.agent.service.AppConversationService> conversations) {
        return new WorkflowService(workflowMapper, runMapper, engine, persistentRunner, conversations);
    }
}
