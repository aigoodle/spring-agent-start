package io.github.aigoodle.rag.agent.config;

import io.github.aigoodle.rag.agent.KnowledgeSearchTool;
import io.github.aigoodle.rag.spi.RagPipeline;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "io.github.aigoodle.knowledge.config.GoodleKnowledgeAutoConfiguration")
@ConditionalOnBean(RagPipeline.class)
public class RagAgentAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    KnowledgeSearchTool knowledgeSearchTool(RagPipeline pipeline) { return new KnowledgeSearchTool(pipeline); }
}
