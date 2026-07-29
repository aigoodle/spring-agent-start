package io.github.aigoodle.model.service;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.CredentialSchema;
import io.github.aigoodle.model.provider.ModelParameterRule;
import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.model.provider.PredefinedModel;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On {@link ApplicationReadyEvent}, walk the in-memory {@link ModelProviderRegistry}
 * and upsert one {@link ProviderDefinitionEntity} + N {@link PredefinedModelEntity}
 * rows per built-in provider. Idempotent: existing rows keep their user-owned
 * {@code enabled}/{@code sort_order}, but their metadata (label, schema,
 * predefined-model list, parameter rules) is refreshed to reflect the current
 * Java source of truth.
 * <p>
 * Rows are written with {@code tenant_id='system'} and {@code source='builtin'}
 * so they're visible to every tenant AND the admin API can tell them apart from
 * user-added rows (which get {@code source='custom'|'external'} and cannot be
 * merged over by a subsequent restart).
 * <p>
 * This is <em>not</em> a bootstrap requirement — the API layer still works if
 * the seeder has not fired yet (falls back to querying Java beans in
 * {@code ModelController}), so a startup failure here does not brick the app.
 */
public class ProviderDefinitionSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProviderDefinitionSeeder.class);

    private final ModelProviderRegistry registry;
    private final ProviderDefinitionService definitionService;

    public ProviderDefinitionSeeder(ModelProviderRegistry registry,
                                    ProviderDefinitionService definitionService) {
        this.registry = registry;
        this.definitionService = definitionService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            seed();
        } catch (Exception e) {
            // Seed is best-effort — never brick startup on it. The API still has a
            // Java fallback in case this fails.
            log.warn("Provider definition seed failed: {}", e.getMessage(), e);
        }
    }

    /** Public so tests can trigger the seed synchronously. */
    public int seed() {
        int seededProviders = 0;
        int seededModels = 0;
        int sortOrder = 0;
        for (ModelProvider provider : registry.all()) {
            ProviderDefinitionEntity def = toDefinition(provider, sortOrder++);
            definitionService.upsert(def);
            seededProviders++;

            int mSort = 0;
            for (PredefinedModel m : provider.predefinedModels()) {
                PredefinedModelEntity row = definitionService.fromMemory(
                        provider.getName(), m, mSort++);
                definitionService.upsertPredefined(row);
                seededModels++;
            }
        }
        log.info("Seeded {} builtin provider definitions ({} predefined model entries) into DB",
                seededProviders, seededModels);
        return seededProviders;
    }

    private ProviderDefinitionEntity toDefinition(ModelProvider provider, int sortOrder) {
        ProviderDefinitionEntity def = new ProviderDefinitionEntity();
        def.setTenantId(ProviderDefinitionService.SYSTEM_TENANT);
        def.setName(provider.getName());
        def.setLabel(provider.getLabel());
        def.setSource("builtin");
        def.setSortOrder(sortOrder);
        def.setEnabled(Boolean.TRUE);
        def.setImplementationKey(provider.implementationKey());
        def.setSupportsRemoteModelListing(provider.supportsRemoteModelListing());

        // Supported model types → JSON array of names.
        Set<ModelType> types = provider.supportedModelTypes();
        List<String> typeNames = new ArrayList<>();
        for (ModelType t : types) typeNames.add(t.name());
        def.setSupportedModelTypes(JsonUtils.toJson(typeNames));

        // Credential schema → JSON array of CredentialField.
        CredentialSchema schema = provider.credentialSchema();
        def.setCredentialSchema(JsonUtils.toJson(schema == null ? List.of() : schema.fields()));

        // Default parameter rules → JSON map<modelType, rules[]>.
        Map<String, List<ModelParameterRule>> rulesByType = new LinkedHashMap<>();
        for (ModelType t : types) {
            List<ModelParameterRule> rules = provider.defaultParameterRules(t);
            if (rules != null && !rules.isEmpty()) {
                rulesByType.put(t.name(), rules);
            }
        }
        if (!rulesByType.isEmpty()) {
            def.setDefaultParameterRules(JsonUtils.toJson(rulesByType));
        }

        // Try to salvage a defaultBaseUrl for OpenAI-compat providers by
        // introspecting the credential schema — the field's defaultValue is
        // typically the vendor's base url.
        if (schema != null) {
            for (var f : schema.fields()) {
                if ("baseUrl".equals(f.getName()) && f.getDefaultValue() != null) {
                    def.setDefaultBaseUrl(String.valueOf(f.getDefaultValue()));
                    break;
                }
            }
        }
        return def;
    }
}
