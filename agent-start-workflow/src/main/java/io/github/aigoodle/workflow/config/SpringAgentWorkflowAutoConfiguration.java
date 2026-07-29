package io.github.aigoodle.workflow.config;

import io.github.aigoodle.knowledge.reader.DocumentExtractor;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.PromptTemplateService;
import io.github.aigoodle.workflow.engine.NodeExecutorRegistry;
import io.github.aigoodle.workflow.engine.WorkflowEngine;
import io.github.aigoodle.workflow.mapper.WorkflowMapper;
import io.github.aigoodle.workflow.mapper.WorkflowRunMapper;
import io.github.aigoodle.workflow.memory.WorkflowConversationMemory;
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
import io.github.aigoodle.workflow.node.builtin.StartNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.TemplateTransformNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.VariableAggregatorNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.VariableAssignerNodeExecutor;
import io.github.aigoodle.workflow.service.WorkflowService;
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

import java.util.List;

/**
 * Auto-configuration for the workflow module. Registers built-in node executors, the
 * DAG engine and the workflow service. Nodes that need optional modules
 * (knowledge / tools / JEXL) are only wired when those modules are on the classpath.
 */
@AutoConfiguration(after = SpringAgentModelAutoConfiguration.class)
@EnableConfigurationProperties(SpringAgentWorkflowProperties.class)
@MapperScan("io.github.aigoodle.workflow.mapper")
public class SpringAgentWorkflowAutoConfiguration {

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

    @Bean
    public HttpRequestNodeExecutor httpRequestNodeExecutor(SpringAgentWorkflowProperties properties) {
        return new HttpRequestNodeExecutor(properties.getHttpBaseUrl());
    }

    @Bean
    public ServiceApiNodeExecutor serviceApiNodeExecutor(SpringAgentWorkflowProperties properties) {
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
                                           ObjectProvider<WorkflowConversationMemory> conversationMemory) {
        return new LlmNodeExecutor(
                modelService,
                promptTemplateService.getIfAvailable(),
                conversationMemory.getIfAvailable());
    }

    @Bean
    public QuestionClassifierNodeExecutor questionClassifierNodeExecutor(ModelService modelService,
                                                                          ObjectProvider<PromptTemplateService> promptTemplateService) {
        return new QuestionClassifierNodeExecutor(modelService, promptTemplateService.getIfAvailable());
    }

    @Bean
    public ParameterExtractorNodeExecutor parameterExtractorNodeExecutor(ModelService modelService) {
        return new ParameterExtractorNodeExecutor(modelService);
    }

    @Bean
    public AgentNodeExecutor agentNodeExecutor(ModelService modelService,
                                               ObjectProvider<ToolCallback> toolCallbacks) {
        return new AgentNodeExecutor(modelService, toolCallbacks.orderedStream().toList());
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
                io.github.aigoodle.tool.ToolRegistry toolRegistry) {
            return new io.github.aigoodle.workflow.node.builtin.ToolNodeExecutor(toolRegistry);
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
                                           WorkflowEngine engine) {
        return new WorkflowService(workflowMapper, runMapper, engine);
    }
}
