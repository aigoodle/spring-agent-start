package io.github.aigoodle.rag.query.config;

import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.rag.query.DirectQueryPlanner;
import io.github.aigoodle.rag.query.ModelQueryPlanner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class RagQueryAutoConfiguration {
    @Bean @ConditionalOnMissingBean(DirectQueryPlanner.class)
    DirectQueryPlanner directQueryPlanner() { return new DirectQueryPlanner(); }
    @Bean @ConditionalOnMissingBean(ModelQueryPlanner.class)
    ModelQueryPlanner modelQueryPlanner(ModelService modelService) { return new ModelQueryPlanner(modelService); }
}
