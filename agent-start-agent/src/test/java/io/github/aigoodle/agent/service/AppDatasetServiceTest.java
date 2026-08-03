package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.service.DatasetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppDatasetServiceTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AppModelConfigService modelConfigService = mock(AppModelConfigService.class);
    private final DatasetService datasetService = mock(DatasetService.class);
    private final AppDatasetService appDatasetService = new AppDatasetService(
            agentMapper, modelConfigService, datasetService);

    @Test
    void rejectsCrossTenantDatasetsAlreadyPresentInStoredConfiguration() {
        AgentEntity application = application("app-1", "tenant-a");
        AppModelConfig configuration = configuration("[\"dataset-b\"]");
        DatasetEntity foreignDataset = dataset("dataset-b", "tenant-b");
        when(agentMapper.selectById("app-1")).thenReturn(application);
        when(modelConfigService.findByAppId("app-1")).thenReturn(configuration);
        when(datasetService.get("dataset-b")).thenReturn(foreignDataset);

        assertThatThrownBy(() -> appDatasetService.list("app-1"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("different tenant");
    }

    @Test
    void appendsOnlyUniqueDatasetIdsWhilePreservingTheirOrder() {
        AgentEntity application = application("app-1", "tenant-a");
        AppModelConfig configuration = configuration("[\"dataset-1\"]");
        when(agentMapper.selectById("app-1")).thenReturn(application);
        when(modelConfigService.findByAppId("app-1")).thenReturn(configuration);
        when(datasetService.get("dataset-1"))
                .thenReturn(dataset("dataset-1", "tenant-a"));
        when(datasetService.get("dataset-2"))
                .thenReturn(dataset("dataset-2", "tenant-a"));
        when(modelConfigService.upsert(any())).thenAnswer(invocation ->
                invocation.<AppModelConfigRegistration>getArgument(0).configuration());

        List<AttachedDatasetView> attached = appDatasetService.attach(
                "app-1", List.of("dataset-1", "dataset-2", "dataset-2"));

        assertThat(attached).extracting(AttachedDatasetView::id)
                .containsExactly("dataset-1", "dataset-2");
        ArgumentCaptor<AppModelConfigRegistration> registration =
                ArgumentCaptor.forClass(AppModelConfigRegistration.class);
        verify(modelConfigService).upsert(registration.capture());
        assertThat(registration.getValue().configuration().getDatasetIdsJson())
                .isEqualTo("[\"dataset-1\",\"dataset-2\"]");
    }

    @Test
    void treatsBlankTenantIdsAsTheDefaultTenant() {
        AgentEntity application = application("app-1", null);
        AppModelConfig configuration = configuration("[\"dataset-1\"]");
        when(agentMapper.selectById("app-1")).thenReturn(application);
        when(modelConfigService.findByAppId("app-1")).thenReturn(configuration);
        when(datasetService.get("dataset-1"))
                .thenReturn(dataset("dataset-1", "default"));

        assertThat(appDatasetService.list("app-1"))
                .extracting(AttachedDatasetView::id)
                .containsExactly("dataset-1");
    }

    private static AgentEntity application(String id, String tenantId) {
        AgentEntity application = new AgentEntity();
        application.setId(id);
        application.setTenantId(tenantId);
        return application;
    }

    private static AppModelConfig configuration(String datasetIdsJson) {
        AppModelConfig configuration = new AppModelConfig();
        configuration.setDatasetIdsJson(datasetIdsJson);
        return configuration;
    }

    private static DatasetEntity dataset(String id, String tenantId) {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(id);
        dataset.setTenantId(tenantId);
        return dataset;
    }
}
