package io.github.aigoodle.memory.config;

import io.github.aigoodle.memory.LayeredMemoryManager;
import io.github.aigoodle.memory.MemoryManager;
import io.github.aigoodle.memory.MemoryStore;
import io.github.aigoodle.memory.mapper.MemoryMapper;
import io.github.aigoodle.memory.store.JdbcMemoryStore;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
    public MemoryManager memoryManager(MemoryStore store, MemoryProperties properties) {
        return new LayeredMemoryManager(store, properties);
    }
}
