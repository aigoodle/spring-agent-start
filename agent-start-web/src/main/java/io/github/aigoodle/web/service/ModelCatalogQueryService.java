package io.github.aigoodle.web.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the read models used by the model-provider administration endpoints. */
@Service
public class ModelCatalogQueryService {

    private final ModelService modelService;
    private final ModelProviderRegistry providerRegistry;
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
        this.definitionService = definitionService;
        this.settingsService = settingsService;
        this.viewAssembler = new ModelProviderViewAssembler(
                modelService, credentialService, definitionService);
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
        definitionService.listPredefined(providerName).stream()
                .map(model -> viewAssembler.toCatalogRow(model, settings, defaults))
                .forEach(catalog::add);
        modelService.listByProvider(tenantId, providerName).stream()
                .map(model -> viewAssembler.toCatalogRow(model, defaults))
                .forEach(catalog::add);
        return catalog;
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
