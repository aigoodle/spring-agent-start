package io.github.aigoodle.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
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
    public DatasetEntity create(CreateDatasetRequest request) {
        DatasetEntity dataset = DatasetDefinitionFactory.create(request);
        datasetMapper.insert(dataset);
        return dataset;
    }

    public DatasetEntity get(String id) {
        return datasetMapper.selectById(id);
    }

    public DatasetEntity require(String id) {
        DatasetEntity dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            throw new AgentException("dataset_not_found", "Dataset not found: " + id, null);
        }
        return dataset;
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
     * untouched. This is the shape of Dify's dataset settings dialog: rename,
     * tweak topK, swap in a different embedding model, etc.
     */
    @Transactional
    public DatasetEntity update(String id, UpdateDatasetRequest patch) {
        DatasetEntity dataset = require(id);
        DatasetPatchApplicator.apply(dataset, patch);
        datasetMapper.updateById(dataset);
        return dataset;
    }

    public ProcessRule processRule(DatasetEntity dataset) {
        ProcessRule processRule = JsonUtils.parse(dataset.getProcessRuleJson(), ProcessRule.class);
        return processRule == null ? ProcessRule.naive() : processRule;
    }

    public RetrievalConfig retrievalConfig(DatasetEntity dataset) {
        RetrievalConfig retrievalConfig = JsonUtils.parse(
                dataset.getRetrievalConfigJson(), RetrievalConfig.class);
        return retrievalConfig == null ? RetrievalConfig.hybrid() : retrievalConfig;
    }

    public void applyCountChange(DatasetEntity dataset, DatasetCountChange change) {
        dataset.setDocumentCount(Math.max(
                0, valueOrZero(dataset.getDocumentCount()) + change.documents()));
        dataset.setSegmentCount(Math.max(
                0, valueOrZero(dataset.getSegmentCount()) + change.segments()));
        datasetMapper.updateById(dataset);
    }

    /** @deprecated Use {@link #applyCountChange(DatasetEntity, DatasetCountChange)}. */
    @Deprecated(forRemoval = false)
    public void updateCounts(DatasetEntity dataset, int deltaDocuments, int deltaSegments) {
        applyCountChange(dataset, new DatasetCountChange(deltaDocuments, deltaSegments));
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
