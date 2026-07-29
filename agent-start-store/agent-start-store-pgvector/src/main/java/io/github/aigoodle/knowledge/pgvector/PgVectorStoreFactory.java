package io.github.aigoodle.knowledge.pgvector;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.index.VectorStoreFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One PgVector-backed table per dataset. Each returned {@link VectorStore} owns its
 * own table so {@link io.github.aigoodle.knowledge.index.VectorStoreManager}'s
 * per-dataset instance model maps directly to physical isolation.
 */
public class PgVectorStoreFactory implements VectorStoreFactory {

    private final JdbcTemplate jdbc;
    private final PgVectorStoreProperties properties;

    public PgVectorStoreFactory(JdbcTemplate jdbc, PgVectorStoreProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public VectorStore create(DatasetEntity dataset, EmbeddingModel embeddingModel) {
        PgVectorStore store = PgVectorStore.builder(jdbc, embeddingModel)
                .schemaName(properties.getSchemaName())
                .vectorTableName(tableName(dataset))
                .initializeSchema(properties.isInitializeSchema())
                .distanceType(properties.getDistanceType())
                .indexType(properties.getIndexType())
                .dimensions(properties.getDimensions())
                .maxDocumentBatchSize(properties.getMaxDocumentBatchSize())
                .build();
        try {
            store.afterPropertiesSet();
        } catch (Exception e) {
            throw new AgentException("pgvector_init_failed",
                    "Failed to initialize pgvector store for dataset " + dataset.getId(), e);
        }
        return store;
    }

    private String tableName(DatasetEntity dataset) {
        String id = dataset.getId() == null ? "default" : dataset.getId();
        return properties.getTablePrefix() + id.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase();
    }
}
