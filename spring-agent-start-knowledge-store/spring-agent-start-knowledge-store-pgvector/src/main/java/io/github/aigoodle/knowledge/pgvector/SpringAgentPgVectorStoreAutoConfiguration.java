package io.github.aigoodle.knowledge.pgvector;

import io.github.aigoodle.knowledge.config.SpringAgentKnowledgeAutoConfiguration;
import io.github.aigoodle.knowledge.index.VectorStoreFactory;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers a {@link PgVectorStoreFactory} when
 * {@code spring-agent.knowledge.vector-store=pgvector}. Runs before the knowledge
 * auto-config so its {@code @ConditionalOnMissingBean(VectorStoreFactory.class)}
 * default (Simple / JDBC) yields to us.
 */
@AutoConfiguration(before = SpringAgentKnowledgeAutoConfiguration.class)
@ConditionalOnClass(PgVectorStore.class)
@ConditionalOnProperty(prefix = "spring-agent.knowledge", name = "vector-store", havingValue = "pgvector")
@EnableConfigurationProperties(PgVectorStoreProperties.class)
public class SpringAgentPgVectorStoreAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(VectorStoreFactory.class)
    public VectorStoreFactory pgVectorStoreFactory(JdbcTemplate jdbcTemplate,
                                                   PgVectorStoreProperties properties) {
        return new PgVectorStoreFactory(jdbcTemplate, properties);
    }
}
