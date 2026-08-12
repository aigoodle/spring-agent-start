package io.github.aigoodle.memory.config;

import io.github.aigoodle.memory.LayeredMemoryManager;
import io.github.aigoodle.memory.MemoryManager;
import io.github.aigoodle.memory.MemoryMaintenance;
import io.github.aigoodle.memory.MemoryStore;
import io.github.aigoodle.memory.mapper.MemoryMapper;
import io.github.aigoodle.memory.store.JdbcMemoryStore;
import io.github.aigoodle.memory.extraction.MemoryExtractor;
import io.github.aigoodle.memory.extraction.RuleBasedMemoryExtractor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(MemoryProperties.class)
@MapperScan("io.github.aigoodle.memory.mapper")
public class SpringAgentMemoryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public MemoryStore memoryStore(MemoryMapper mapper) {
        return new JdbcMemoryStore(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryExtractor ruleBasedMemoryExtractor() {
        return new RuleBasedMemoryExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryManager memoryManager(MemoryStore store, MemoryProperties properties,
                                       List<MemoryExtractor> extractors) {
        return new LayeredMemoryManager(store, properties, extractors);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryMaintenance memoryMaintenance(MemoryStore store) {
        return new MemoryMaintenance(store);
    }
}
