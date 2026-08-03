package io.github.aigoodle.web.support;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.entity.ProviderCredentialEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.CredentialField;
import io.github.aigoodle.model.provider.ModelParameterRule;
import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.model.provider.PredefinedModel;
import io.github.aigoodle.model.provider.RemoteModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts model-domain objects into the stable map shapes exposed by the web API.
 *
 * <p>The mapper is deliberately stateless: tenant-aware enrichment and persistence
 * lookups belong to the controller-facing assembler, while this class only translates
 * values. Keeping those concerns separate makes the response contract readable and
 * allows it to be tested without starting Spring.</p>
 */
public final class ModelViewMapper {

    private ModelViewMapper() {
    }

    public static ProviderDefinitionEntity toProviderDefinition(Map<String, Object> payload) {
        ProviderDefinitionEntity definition = new ProviderDefinitionEntity();
        definition.setName((String) payload.get("name"));
        definition.setLabel(payload.get("label") == null
                ? (String) payload.get("name") : (String) payload.get("label"));
        definition.setDescription((String) payload.get("description"));
        definition.setIcon((String) payload.get("icon"));
        definition.setSvgIcon((String) payload.get("svgIcon"));
        definition.setImplementationKey((String) payload.get("implementationKey"));
        definition.setDefaultBaseUrl((String) payload.get("defaultBaseUrl"));
        definition.setSource((String) payload.get("source"));
        if (payload.get("sortOrder") instanceof Number sortOrder) {
            definition.setSortOrder(sortOrder.intValue());
        }
        if (payload.get("enabled") instanceof Boolean enabled) {
            definition.setEnabled(enabled);
        }
        if (payload.get("supportsRemoteModelListing") instanceof Boolean supportsRemoteListing) {
            definition.setSupportsRemoteModelListing(supportsRemoteListing);
        }
        if (payload.containsKey("supportedModelTypes")) {
            definition.setSupportedModelTypes(JsonUtils.toJson(payload.get("supportedModelTypes")));
        }
        if (payload.containsKey("credentialSchema")) {
            definition.setCredentialSchema(JsonUtils.toJson(payload.get("credentialSchema")));
        }
        if (payload.containsKey("defaultParameterRules")) {
            definition.setDefaultParameterRules(JsonUtils.toJson(payload.get("defaultParameterRules")));
        }
        return definition;
    }

    public static Map<String, Object> toGroupedProviderView(
            ProviderDefinitionEntity definition, List<Map<String, Object>> models) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", definition.getName());
        view.put("provider", definition.getName());
        view.put("label", definition.getLabel());
        view.put("description", definition.getDescription());

        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("icon", definition.getIcon());
        declaration.put("svg_icon", definition.getSvgIcon());
        view.put("declaration", declaration);
        view.put("modelList", models);
        return view;
    }

    public static Map<String, Object> toGroupedModelView(
            String providerName, String modelName, ModelType modelType) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", providerName + "::" + modelName + "::" + modelType.name());
        view.put("providerName", providerName);
        view.put("modelName", modelName);
        view.put("modelType", modelType.name());
        return view;
    }

    public static Map<String, Object> toCredentialView(
            ModelProvider provider, ProviderCredentialEntity credential) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("providerName", provider.getName());
        view.put("configured", credential != null);
        if (credential != null) {
            view.put("credentialId", credential.getId());
            view.put("credentialName", credential.getCredentialName());
        }
        return view;
    }

    public static Map<String, Object> toCredentialFieldView(CredentialField field) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", field.getName());
        view.put("label", field.getLabel());
        view.put("type", field.getType());
        view.put("required", field.isRequired());
        view.put("secret", field.isSecret());
        view.put("defaultValue", field.getDefaultValue());
        view.put("placeholder", field.getPlaceholder());
        return view;
    }

    public static Map<String, Object> toPredefinedModelView(PredefinedModel model) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("model", model.getModel());
        view.put("label", model.getLabel());
        view.put("modelType", model.getModelType());
        view.put("features", model.getFeatures());
        view.put("contextLength", model.getContextLength());
        view.put("dimensions", model.getDimensions());
        if (model.getParameterRules() != null && !model.getParameterRules().isEmpty()) {
            view.put("parameterRules", model.getParameterRules().stream()
                    .map(ModelViewMapper::toParameterRuleView)
                    .toList());
        }
        return view;
    }

    public static Map<String, Object> toParameterRuleView(ModelParameterRule rule) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", rule.getName());
        view.put("label", rule.getLabel());
        view.put("type", rule.getType());
        view.put("min", rule.getMin());
        view.put("max", rule.getMax());
        view.put("step", rule.getStep());
        view.put("precision", rule.getPrecision());
        view.put("defaultValue", rule.getDefaultValue());
        view.put("placeholder", rule.getPlaceholder());
        view.put("help", rule.getHelp());
        view.put("required", rule.isRequired());
        return view;
    }

    public static Map<String, Object> toRemoteModelView(RemoteModel model) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("modelId", model.getModelId());
        view.put("label", model.getLabel());
        view.put("modelType", model.getModelType());
        view.put("contextLength", model.getContextLength());
        view.put("dimensions", model.getDimensions());
        view.put("features", model.getFeatures());
        view.put("ownedBy", model.getOwnedBy());
        view.put("typeInferred", model.isTypeInferred());
        return view;
    }
}
