package io.github.aigoodle.knowledge.index;

import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import io.github.aigoodle.model.service.ModelService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides a {@link VectorStore} per dataset. The default implementation is an
 * in-memory {@link SimpleVectorStore} bound to the dataset's embedding model, so the
 * module works with zero external infrastructure; a custom {@link VectorStoreFactory}
 * bean can swap in Elasticsearch/Milvus/PgVector per dataset.
 * <p>
 * Each dataset owning its own store means dataset isolation is structural — no
 * tenant/dataset filter is needed on similarity search.
 */
public class VectorStoreManager {

    private final ModelService modelService;
    private final VectorStoreFactory factory;
    private final ConcurrentHashMap<String, VectorStore> stores = new ConcurrentHashMap<>();

    public VectorStoreManager(ModelService modelService, VectorStoreFactory factory) {
        this.modelService = modelService;
        this.factory = factory;
    }

    public boolean hasVectorIndex(DatasetEntity dataset) {
        return dataset.getIndexingTechnique() != IndexingTechnique.ECONOMY
                && dataset.getEmbeddingModelId() != null;
    }

    public VectorStore getStore(DatasetEntity dataset) {
        return stores.computeIfAbsent(dataset.getId(), id -> create(dataset));
    }

    private VectorStore create(DatasetEntity dataset) {
        EmbeddingModel embeddingModel = modelService.getEmbeddingModel(dataset.getEmbeddingModelId());
        if (factory != null) {
            VectorStore custom = factory.create(dataset, embeddingModel);
            if (custom != null) {
                return custom;
            }
        }
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    public void evict(String datasetId) {
        stores.remove(datasetId);
    }
}
