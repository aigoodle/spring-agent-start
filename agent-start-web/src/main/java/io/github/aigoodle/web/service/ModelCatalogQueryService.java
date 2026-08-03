package io.github.aigoodle.web.service;

import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.entity.ProviderModelSettingEntity;
import io.github.aigoodle.model.entity.TenantDefaultModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelParameterRule;
import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.ProviderCredentialService;
import io.github.aigoodle.model.service.ProviderDefinitionService;
import io.github.aigoodle.model.service.ProviderModelSettingsService;
import io.github.aigoodle.web.support.ModelProviderViewAssembler;
import io.github.aigoodle.web.support.ModelViewMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the read models used by the model-provider administration endpoints. */
@Service
public class ModelCatalogQueryService {

    private final ModelService modelService;
    private final ModelProviderRegistry providerRegistry;
    private final ProviderCredentialService credentialService;
    private final ProviderDefinitionService definitionService;
    private final ProviderModelSettingsService settingsService;
    private final ModelProviderViewAssembler viewAssembler;

    public ModelCatalogQueryService(ModelService modelService,
                                    ModelProviderRegistry providerRegistry,
                                    ProviderCredentialService credentialService,
                                    ProviderDefinitionService definitionService,
                                    ProviderModelSettingsService settingsService) {
        this.modelService = modelService;
        this.providerRegistry = providerRegistry;
        this.credentialService = credentialService;
        this.definitionService = definitionService;
        this.settingsService = settingsService;
        this.viewAssembler = new ModelProviderViewAssembler(
                modelService, credentialService, definitionService, settingsService);
    }

    public List<Map<String, Object>> providers(String tenantId) {
        List<ProviderDefinitionEntity> definitions = definitionService.list(tenantId);
        if (!definitions.isEmpty()) {
            return definitions.stream()
                    .map(definition -> viewAssembler.toProviderView(definition, tenantId))
                    .toList();
        }
        return providerRegistry.all().stream()
                .map(provider -> viewAssembler.toProviderView(provider, tenantId))
                .toList();
    }

    public Map<String, Object> provider(String providerName, String tenantId) {
        ProviderDefinitionEntity definition = definitionService.findByName(tenantId, providerName);
        if (definition != null) {
            return viewAssembler.toProviderView(definition, tenantId);
        }
        ModelProvider provider = providerRegistry.get(providerName);
        return viewAssembler.toProviderView(provider, tenantId);
    }

    public List<Map<String, Object>> catalog(String providerName, String tenantId) {
        List<Map<String, Object>> catalog = new ArrayList<>();
        Map<String, ProviderModelSettingEntity> settings =
                settingsService.settingIndex(tenantId, providerName);
        Map<ModelType, TenantDefaultModelEntity> defaults = settingsService.listDefaults(tenantId);
        // (model, type) triples owned by the predefined catalog. A custom agent_model
        // row duplicating one of them is a runtime materialization artifact (see
        // ModelService#findOrMaterialize — default resolution and by-name chat
        // lookups lazily insert such rows). Rendering it as a second catalog row
        // shows the same model twice with two conflicting enable switches, so the
        // predefined row wins and the duplicate is dropped.
        Set<String> predefinedTriples = new HashSet<>();
        for (PredefinedModelEntity predefinedModel : definitionService.listPredefined(providerName)) {
            predefinedTriples.add(triple(predefinedModel.getModel(), predefinedModel.getModelType()));
            catalog.add(viewAssembler.toCatalogRow(predefinedModel, settings, defaults));
        }
        modelService.listByProvider(tenantId, providerName).stream()
                .filter(model -> !predefinedTriples.contains(
                        triple(model.getModelName(), model.getModelType())))
                .map(model -> viewAssembler.toCatalogRow(model, defaults))
                .forEach(catalog::add);
        return catalog;
    }

    /**
     * Candidates for the "系统默认模型" dropdowns (and the shared model picker),
     * grouped by {@link ModelType} and then by provider.
     *
     * <p>Only models whose switch is ON are eligible — this is what keeps the
     * dropdown usable for providers whose catalog has dozens or hundreds of
     * entries:
     * <ul>
     *   <li>predefined rows require an explicit {@code enabled=true}
     *       {@code agent_provider_model_setting} row (missing row = disabled —
     *       the same opt-in state the catalog popover switch writes);</li>
     *   <li>custom {@code agent_model} rows require {@code enabled=true} on the
     *       row itself (the same flag the popover switch PATCHes).</li>
     * </ul>
     *
     * <p>A custom row duplicating a predefined (provider, model, type) triple is
     * skipped entirely: such rows are materialized lazily by
     * {@link ModelService#findOrMaterialize} for runtime use, and their
     * enable-state is owned by the settings table. Letting them through would
     * (1) list the same model twice and (2) resurrect models the user explicitly
     * switched off (materialized rows carry {@code enabled=true}).
     */
    public Map<String, List<Map<String, Object>>> groupedModelsByType(String tenantId) {
        Map<String, List<Map<String, Object>>> providersByModelType = new LinkedHashMap<>();
        // Seed empty buckets so the UI can render every supported type even when
        // there are no candidates yet (keeps the response shape stable).
        for (ModelType modelType : ModelType.values()) {
            providersByModelType.put(modelType.name(), new ArrayList<>());
        }

        for (ProviderDefinitionEntity definition : definitionService.list(tenantId)) {
            // A provider only surfaces once its credential has been saved — the
            // models behind it wouldn't be invocable otherwise.
            if (credentialService.findPrimary(tenantId, definition.getName()) == null) {
                continue;
            }

            Map<String, ProviderModelSettingEntity> settings =
                    settingsService.settingIndex(tenantId, definition.getName());

            // Per-type buckets keyed by model name so each (provider, model, type)
            // triple produces at most one option.
            Map<ModelType, Map<String, Map<String, Object>>> modelsByType = new LinkedHashMap<>();
            Set<String> predefinedTriples = new HashSet<>();

            for (PredefinedModelEntity predefinedModel
                    : definitionService.listPredefined(definition.getName())) {
                ModelType modelType = predefinedModel.getModelType();
                predefinedTriples.add(triple(predefinedModel.getModel(), modelType));
                ProviderModelSettingEntity setting = settings.get(
                        predefinedModel.getModel() + "::" + modelType.name());
                if (setting == null || !Boolean.TRUE.equals(setting.getEnabled())) {
                    continue; // switch off (or never turned on) — not selectable
                }
                modelsByType.computeIfAbsent(modelType, ignored -> new LinkedHashMap<>())
                        .put(predefinedModel.getModel(), ModelViewMapper.toGroupedModelView(
                                definition.getName(), predefinedModel.getModel(), modelType));
            }

            for (ModelEntity customModel
                    : modelService.listByProvider(tenantId, definition.getName())) {
                // Triples covered by the predefined catalog are gated exclusively
                // by the settings table above — a stale materialized agent_model
                // row must not sneak a switched-off model back into the dropdown.
                if (predefinedTriples.contains(
                        triple(customModel.getModelName(), customModel.getModelType()))) {
                    continue;
                }
                if (!Boolean.TRUE.equals(customModel.getEnabled())) {
                    continue; // switch off
                }
                modelsByType.computeIfAbsent(customModel.getModelType(), ignored -> new LinkedHashMap<>())
                        .put(customModel.getModelName(), ModelViewMapper.toGroupedModelView(
                                definition.getName(), customModel.getModelName(),
                                customModel.getModelType()));
            }

            // Publish one provider entry per model type with a non-empty bucket.
            for (Map.Entry<ModelType, Map<String, Map<String, Object>>> entry
                    : modelsByType.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                providersByModelType.get(entry.getKey().name()).add(
                        ModelViewMapper.toGroupedProviderView(
                                definition, new ArrayList<>(entry.getValue().values())));
            }
        }
        return providersByModelType;
    }

    private static String triple(String modelName, ModelType modelType) {
        return modelName + "::" + (modelType == null ? "" : modelType.name());
    }

    public Map<String, Object> parameters(String modelId) {
        List<ModelParameterRule> rules = modelService.parameterRulesFor(modelId);
        Map<String, Object> storedValues = modelService.getModelProperties(modelId);
        Map<String, Object> configuredValues = new LinkedHashMap<>();
        for (ModelParameterRule rule : rules) {
            Object configuredValue = storedValues.get(rule.getName());
            if (configuredValue != null) {
                configuredValues.put(rule.getName(), configuredValue);
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rules", rules.stream().map(ModelViewMapper::toParameterRuleView).toList());
        view.put("parameters", configuredValues);
        return view;
    }

}
