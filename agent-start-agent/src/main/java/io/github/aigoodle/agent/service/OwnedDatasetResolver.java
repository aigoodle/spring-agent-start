package io.github.aigoodle.agent.service;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.service.DatasetService;

import java.util.Objects;

/** Resolves a dataset and enforces that it belongs to the application's tenant. */
final class OwnedDatasetResolver {

    private static final String DEFAULT_TENANT = "default";

    private final DatasetService datasetService;

    OwnedDatasetResolver(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    DatasetEntity requireOwned(String datasetId, String applicationTenantId) {
        DatasetEntity dataset = datasetService.get(datasetId);
        if (dataset == null) {
            throw new AgentException(
                    "dataset_not_found", "Dataset not found: " + datasetId, null);
        }
        if (!Objects.equals(
                effectiveTenant(dataset.getTenantId()),
                effectiveTenant(applicationTenantId))) {
            throw new AgentException(
                    "dataset_cross_tenant",
                    "Dataset " + datasetId + " belongs to a different tenant",
                    null);
        }
        return dataset;
    }

    private static String effectiveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
    }
}
