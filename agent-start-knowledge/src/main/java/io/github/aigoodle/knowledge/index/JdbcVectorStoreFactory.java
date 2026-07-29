package io.github.aigoodle.knowledge.index;

import io.github.aigoodle.knowledge.entity.DatasetEntity;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds a {@link JdbcVectorStore} per dataset so embeddings are persisted in the same
 * relational database as the rest of the data. Enabled by publishing this as a bean
 * (the auto-configuration does so when {@code spring-agent.knowledge.vector-store=jdbc}).
 */
public class JdbcVectorStoreFactory implements VectorStoreFactory {

    public static final String DEFAULT_TABLE = "embeddings";

    private final JdbcTemplate jdbc;
    private final String table;

    public JdbcVectorStoreFactory(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table == null || table.isBlank() ? DEFAULT_TABLE : table;
    }

    @Override
    public VectorStore create(DatasetEntity dataset, EmbeddingModel embeddingModel) {
        return new JdbcVectorStore(jdbc, embeddingModel, dataset.getId(), table);
    }
}
