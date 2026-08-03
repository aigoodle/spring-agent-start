package io.github.aigoodle.web.support;

import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderCredentialEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.entity.ProviderModelSettingEntity;
import io.github.aigoodle.model.entity.TenantDefaultModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.CredentialField;
import io.github.aigoodle.model.provider.ModelParameterRule;
import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.ProviderCredentialService;
import io.github.aigoodle.model.service.ProviderDefinitionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enriches model-provider response views with persisted catalog and tenant state.
 *
 * <p>This class is constructed by the controller from its existing collaborators,
 * so extracting presentation logic does not change the Spring bean graph or the
 * controller's public constructor.</p>
 */
public final class ModelProviderViewAssembler {

    private final ModelService modelService;
    private final ProviderCredentialService credentialService;
    private final ProviderDefinitionService definitionService;

    public ModelProviderViewAssembler(ModelService modelService,
                                      ProviderCredentialService credentialService,
                                      ProviderDefinitionService definitionService) {
        this.modelService = modelService;
        this.credentialService = credentialService;
        this.definitionService = definitionService;
    }

    public Map<String, Object> toProviderView(
            ProviderDefinitionEntity definition, String tenantId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", definition.getId());
        view.put("name", definition.getName());
        view.put("label", definition.getLabel());
        view.put("description", definition.getDescription());
        view.put("icon", definition.getIcon());
        view.put("svgIcon", definition.getSvgIcon());
        view.put("implementationKey", definition.getImplementationKey());
        view.put("defaultBaseUrl", definition.getDefaultBaseUrl());
        view.put("source", definition.getSource());
        view.put("sortOrder", definition.getSortOrder());
        view.put("enabled", definition.getEnabled());
        view.put("supportsRemoteModelListing",
                Boolean.TRUE.equals(definition.getSupportsRemoteModelListing()));

        Set<ModelType> supportedTypes =
                definitionService.deserializeModelTypes(definition.getSupportedModelTypes());
        view.put("supportedModelTypes", supportedTypes);
        List<CredentialField> credentialFields =
                definitionService.deserializeCredentialSchema(definition.getCredentialSchema());
        view.put("credentialSchema", credentialFields.stream()
                .map(ModelViewMapper::toCredentialFieldView)
                .toList());
        view.put("defaultParameterRules",
                defaultRulesByModelType(definition, supportedTypes));
        view.put("predefinedModels", definitionService.listPredefined(definition.getName()).stream()
                .map(this::toPredefinedModelRow)
                .toList());

        addTenantState(view, tenantId, definition.getName(),
                credentialFields.stream().filter(CredentialField::isSecret)
                        .map(CredentialField::getName).toList());
        return view;
    }

    public Map<String, Object> toProviderView(ModelProvider provider, String tenantId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", provider.getName());
        view.put("label", provider.getLabel());
        view.put("implementationKey", provider.implementationKey());
        view.put("source", "builtin");
        view.put("supportedModelTypes", provider.supportedModelTypes());
        view.put("credentialSchema", provider.credentialSchema().fields().stream()
                .map(ModelViewMapper::toCredentialFieldView).toList());
        view.put("predefinedModels", provider.predefinedModels().stream()
                .map(ModelViewMapper::toPredefinedModelView).toList());

        Map<String, Object> defaultRules = new LinkedHashMap<>();
        for (ModelType modelType : provider.supportedModelTypes()) {
            defaultRules.put(modelType.name(), provider.defaultParameterRules(modelType).stream()
                    .map(ModelViewMapper::toParameterRuleView).toList());
        }
        view.put("defaultParameterRules", defaultRules);
        view.put("supportsRemoteModelListing", provider.supportsRemoteModelListing());
        addTenantState(view, tenantId, provider.getName(),
                provider.credentialSchema().secretFieldNames());
        return view;
    }

    public Map<String, Object> toCatalogRow(
            PredefinedModelEntity predefinedModel,
            Map<String, ProviderModelSettingEntity> settings,
            Map<ModelType, TenantDefaultModelEntity> defaults) {
        Map<String, Object> row = toPredefinedModelRow(predefinedModel);
        ProviderModelSettingEntity setting = settings.get(
                predefinedModel.getModel() + "::" + predefinedModel.getModelType().name());
        row.put("enabled", setting != null && Boolean.TRUE.equals(setting.getEnabled()));
        row.put("loadBalancingEnabled",
                setting != null && Boolean.TRUE.equals(setting.getLoadBalancingEnabled()));
        row.put("isDefault", isDefault(predefinedModel.getProviderName(),
                predefinedModel.getModel(), defaults.get(predefinedModel.getModelType())));
        return row;
    }

    public Map<String, Object> toCatalogRow(
            ModelEntity model, Map<ModelType, TenantDefaultModelEntity> defaults) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", model.getId());
        row.put("model", model.getModelName());
        row.put("label", model.getModelName());
        row.put("modelType", model.getModelType());
        row.put("credentialId", model.getCredentialId());
        row.put("enabled", model.getEnabled());
        row.put("source", "custom");
        row.put("isDefault", isDefault(model.getProviderName(), model.getModelName(),
                defaults.get(model.getModelType())));
        return row;
    }

    private Map<String, Object> defaultRulesByModelType(
            ProviderDefinitionEntity definition, Set<ModelType> supportedTypes) {
        Map<ModelType, List<ModelParameterRule>> rulesByType =
                definitionService.deserializeParameterRules(definition.getDefaultParameterRules());
        Map<String, Object> viewsByType = new LinkedHashMap<>();
        for (ModelType modelType : supportedTypes) {
            viewsByType.put(modelType.name(), rulesByType.getOrDefault(modelType, List.of()).stream()
                    .map(ModelViewMapper::toParameterRuleView).toList());
        }
        return viewsByType;
    }

    private Map<String, Object> toPredefinedModelRow(PredefinedModelEntity model) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", model.getId());
        view.put("model", model.getModel());
        view.put("label", model.getLabel());
        view.put("modelType", model.getModelType());
        view.put("contextLength", model.getContextLength());
        view.put("dimensions", model.getDimensions());
        view.put("features", definitionService.deserializeFeatures(model.getFeatures()));
        view.put("source", "predefined");
        if (model.getParameterRules() != null) {
            view.put("parameterRules",
                    definitionService.deserializeRuleList(model.getParameterRules()).stream()
                            .map(ModelViewMapper::toParameterRuleView).toList());
        }
        return view;
    }

    private void addTenantState(Map<String, Object> view, String tenantId,
                                String providerName, List<String> secretFieldNames) {
        ProviderCredentialEntity credential = credentialService.findPrimary(tenantId, providerName);
        view.put("credentialConfigured", credential != null);
        if (credential != null) {
            view.put("credentialId", credential.getId());
            view.put("credentialMasked",
                    credentialService.maskedView(credential, secretFieldNames));
        }
        long installedModelCount = modelService.list(tenantId).stream()
                .filter(model -> providerName.equalsIgnoreCase(model.getProviderName()))
                .count();
        view.put("installedModelCount", Math.toIntExact(installedModelCount));
    }

    private static boolean isDefault(String providerName, String modelName,
                                     TenantDefaultModelEntity defaultModel) {
        return defaultModel != null
                && modelName.equalsIgnoreCase(defaultModel.getModelName())
                && providerName.equalsIgnoreCase(defaultModel.getProviderName());
    }
}
