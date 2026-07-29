package io.github.aigoodle.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import io.github.aigoodle.knowledge.mapper.DatasetMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD for datasets (knowledge bases) and access to their parsed configuration.
 */
public class DatasetService {

    private final DatasetMapper datasetMapper;

    public DatasetService(DatasetMapper datasetMapper) {
        this.datasetMapper = datasetMapper;
    }

    @Transactional
    public DatasetEntity create(CreateDatasetRequest req) {
        if (req.getIndexingTechnique() == IndexingTechnique.HIGH_QUALITY
                && (req.getEmbeddingModelId() == null || req.getEmbeddingModelId().isBlank())) {
            throw new AgentException("embedding_model_required",
                    "A high-quality dataset requires an embedding model id", null);
        }
        DatasetEntity entity = new DatasetEntity();
        entity.setTenantId(req.getTenantId());
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());
        entity.setEmbeddingModelId(req.getEmbeddingModelId());
        entity.setIndexingTechnique(req.getIndexingTechnique() == null
                ? IndexingTechnique.HIGH_QUALITY : req.getIndexingTechnique());
        entity.setProcessRuleJson(JsonUtils.toJson(req.getProcessRule() == null
                ? ProcessRule.naive() : req.getProcessRule()));
        entity.setRetrievalConfigJson(JsonUtils.toJson(req.getRetrievalConfig() == null
                ? RetrievalConfig.hybrid() : req.getRetrievalConfig()));
        entity.setVectorStore(req.getVectorStore());
        entity.setDocumentCount(0);
        entity.setSegmentCount(0);
        datasetMapper.insert(entity);
        return entity;
    }

    public DatasetEntity get(String id) {
        return datasetMapper.selectById(id);
    }

    public DatasetEntity require(String id) {
        DatasetEntity entity = datasetMapper.selectById(id);
        if (entity == null) {
            throw new AgentException("dataset_not_found", "Dataset not found: " + id, null);
        }
        return entity;
    }

    public List<DatasetEntity> list(String tenantId) {
        return datasetMapper.selectList(new LambdaQueryWrapper<DatasetEntity>()
                .eq(DatasetEntity::getTenantId, tenantId == null ? "default" : tenantId));
    }

    @Transactional
    public void delete(String id) {
        datasetMapper.deleteById(id);
    }

    /**
     * Edit dataset metadata + retrieval/chunk settings. Missing fields are left
     * untouched — this is the shape of Dify's dataset settings dialog: rename,
     * tweak topK, swap in a different embedding model, etc.
     */
    @Transactional
    public DatasetEntity update(String id, UpdateDatasetRequest req) {
        DatasetEntity entity = require(id);
        if (req.getName() != null && !req.getName().isBlank()) entity.setName(req.getName());
        if (req.getDescription() != null) entity.setDescription(req.getDescription());
        if (req.getEmbeddingModelId() != null) entity.setEmbeddingModelId(req.getEmbeddingModelId());
        if (req.getIndexingTechnique() != null) entity.setIndexingTechnique(req.getIndexingTechnique());
        if (req.getProcessRule() != null) entity.setProcessRuleJson(JsonUtils.toJson(req.getProcessRule()));
        if (req.getRetrievalConfig() != null) entity.setRetrievalConfigJson(JsonUtils.toJson(req.getRetrievalConfig()));
        if (req.getVectorStore() != null) entity.setVectorStore(req.getVectorStore());
        datasetMapper.updateById(entity);
        return entity;
    }

    public ProcessRule processRule(DatasetEntity dataset) {
        ProcessRule rule = JsonUtils.parse(dataset.getProcessRuleJson(), ProcessRule.class);
        return rule == null ? ProcessRule.naive() : rule;
    }

    public RetrievalConfig retrievalConfig(DatasetEntity dataset) {
        RetrievalConfig cfg = JsonUtils.parse(dataset.getRetrievalConfigJson(), RetrievalConfig.class);
        return cfg == null ? RetrievalConfig.hybrid() : cfg;
    }

    public void updateCounts(DatasetEntity dataset, int deltaDocuments, int deltaSegments) {
        dataset.setDocumentCount(Math.max(0, nz(dataset.getDocumentCount()) + deltaDocuments));
        dataset.setSegmentCount(Math.max(0, nz(dataset.getSegmentCount()) + deltaSegments));
        datasetMapper.updateById(dataset);
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }
}
