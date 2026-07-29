package io.github.aigoodle.knowledge.elasticsearch;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.index.VectorStoreFactory;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;

/**
 * One Elasticsearch index per dataset; the {@link ElasticsearchVectorStore} is
 * instantiated on demand by {@link io.github.aigoodle.knowledge.index.VectorStoreManager}
 * and its {@code initializeSchema=true} flag creates the mapping on first write.
 */
public class ElasticsearchVectorStoreFactory implements VectorStoreFactory {

    private final RestClient restClient;
    private final ElasticsearchStoreProperties properties;

    public ElasticsearchVectorStoreFactory(RestClient restClient,
                                           ElasticsearchStoreProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public VectorStore create(DatasetEntity dataset, EmbeddingModel embeddingModel) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(indexName(dataset));
        options.setSimilarity(properties.getSimilarity());
        if (properties.getDimensions() > 0) {
            options.setDimensions(properties.getDimensions());
        }

        ElasticsearchVectorStore store = ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(true)
                .build();
        try {
            store.afterPropertiesSet();
        } catch (Exception e) {
            throw new AgentException("elasticsearch_init_failed",
                    "Failed to initialize Elasticsearch store for dataset " + dataset.getId(), e);
        }
        return store;
    }

    private String indexName(DatasetEntity dataset) {
        String id = dataset.getId() == null ? "default" : dataset.getId();
        return properties.getIndexPrefix() + id.replaceAll("[^A-Za-z0-9_-]", "-").toLowerCase();
    }
}
