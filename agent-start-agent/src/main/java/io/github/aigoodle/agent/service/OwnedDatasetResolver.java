package io.github.aigoodle.agent.service;

import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.service.DatasetService;

/** Resolves a dataset and enforces that it belongs to the application's tenant. */
final class OwnedDatasetResolver {

    private final DatasetService datasetService;

    OwnedDatasetResolver(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    DatasetEntity requireOwned(String datasetId, String applicationTenantId) {
        return datasetService.require(applicationTenantId, datasetId);
    }
}
