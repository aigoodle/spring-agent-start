package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.service.DatasetService;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages knowledge bases attached to an application model configuration.
 *
 * <p>Every read and write passes through the same ownership resolver, so stale or
 * manually edited sidecars cannot expose datasets from another tenant.</p>
 */
public class AppDatasetService {

    private final AgentMapper agentMapper;
    private final AppModelConfigService modelConfigService;
    private final OwnedDatasetResolver datasetResolver;

    public AppDatasetService(AgentMapper agentMapper,
                             AppModelConfigService modelConfigService,
                             DatasetService datasetService) {
        this.agentMapper = agentMapper;
        this.modelConfigService = modelConfigService;
        this.datasetResolver = new OwnedDatasetResolver(datasetService);
    }

    /** Return hydrated summaries for the datasets currently attached to an app. */
    public List<AttachedDatasetView> list(String appId) {
        AgentEntity application = requireApplication(appId);
        return hydrate(attachedDatasetIds(appId), application.getTenantId());
    }

    /** Add datasets while retaining the existing order and removing duplicates. */
    @Transactional
    public List<AttachedDatasetView> attach(String appId, List<String> datasetIds) {
        AgentEntity application = requireApplication(appId);
        Set<String> combinedIds = new LinkedHashSet<>(attachedDatasetIds(appId));
        combinedIds.addAll(validateDatasetIds(datasetIds, application.getTenantId()));
        persistAttachedIds(application, combinedIds);
        return hydrate(combinedIds, application.getTenantId());
    }

    /** Remove one dataset id. Missing attachments are treated as an idempotent no-op. */
    @Transactional
    public List<AttachedDatasetView> detach(String appId, String datasetId) {
        AgentEntity application = requireApplication(appId);
        Set<String> remainingIds = new LinkedHashSet<>(attachedDatasetIds(appId));
        remainingIds.remove(datasetId);
        persistAttachedIds(application, remainingIds);
        return hydrate(remainingIds, application.getTenantId());
    }

    /** Replace the complete attachment set in the supplied order. */
    @Transactional
    public List<AttachedDatasetView> replace(String appId, List<String> datasetIds) {
        AgentEntity application = requireApplication(appId);
        Set<String> replacementIds = validateDatasetIds(
                datasetIds, application.getTenantId());
        persistAttachedIds(application, replacementIds);
        return hydrate(replacementIds, application.getTenantId());
    }

    private Set<String> validateDatasetIds(List<String> datasetIds, String tenantId) {
        Set<String> validatedIds = new LinkedHashSet<>();
        for (String datasetId : datasetIds == null ? List.<String>of() : datasetIds) {
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }
            datasetResolver.requireOwned(datasetId, tenantId);
            validatedIds.add(datasetId);
        }
        return validatedIds;
    }

    private List<AttachedDatasetView> hydrate(Iterable<String> datasetIds, String tenantId) {
        List<AttachedDatasetView> datasets = new ArrayList<>();
        for (String datasetId : datasetIds) {
            datasets.add(AttachedDatasetView.from(
                    datasetResolver.requireOwned(datasetId, tenantId)));
        }
        return datasets;
    }

    private List<String> attachedDatasetIds(String appId) {
        AppModelConfig configuration = modelConfigService.findByAppId(appId);
        return configuration == null
                ? List.of()
                : JsonUtils.parseList(configuration.getDatasetIdsJson(), String.class);
    }

    private void persistAttachedIds(AgentEntity application, Iterable<String> datasetIds) {
        List<String> orderedIds = new ArrayList<>();
        datasetIds.forEach(orderedIds::add);
        AppModelConfig patch = new AppModelConfig();
        patch.setDatasetIdsJson(JsonUtils.toJson(orderedIds));
        modelConfigService.upsert(new AppModelConfigRegistration(
                application.getId(), application.getTenantId(), patch));
    }

    private AgentEntity requireApplication(String appId) {
        AgentEntity application = agentMapper.selectById(appId);
        if (application == null) {
            throw new AgentException("agent_not_found", "Agent not found: " + appId, null);
        }
        return application;
    }
}
