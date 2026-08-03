package io.github.aigoodle.web.controller;

import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderCredentialEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.model.provider.RemoteModel;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import io.github.aigoodle.model.service.ModelRegistration;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.ProviderCredentialService;
import io.github.aigoodle.model.service.ProviderDefinitionService;
import io.github.aigoodle.model.service.ProviderModelSettingsService;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.dto.ProviderCredentialRequest;
import io.github.aigoodle.web.service.ModelCatalogQueryService;
import io.github.aigoodle.web.support.ModelViewMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST facade over {@link ModelService} + {@link ModelProviderRegistry}. Powers the
 * model-provider settings surface:
 * <ul>
 *   <li>list providers with their credential schema, predefined models, and — critically
 *       for the Dify-parity "填 key 自动拉取" flow — the tenant's own configuration state
 *       (whether credentials are saved, how many models are installed).</li>
 *   <li>save/query/clear a provider-level credential; call the vendor to list what that
 *       key unlocks; batch-register selected entries.</li>
 *   <li>the existing per-model CRUD, kept for advanced users and non-catalog providers.</li>
 * </ul>
 */
@RestController
@RequestMapping("${spring-agent.web.base-path:}")
public class ModelController {

    private final ModelService modelService;
    private final ModelProviderRegistry providerRegistry;
    private final ProviderCredentialService credentialService;
    private final ProviderDefinitionService definitionService;
    private final ProviderModelSettingsService settingsService;
    private final ModelCatalogQueryService catalogQueries;

    public ModelController(ModelService modelService, ModelProviderRegistry providerRegistry,
                           ProviderCredentialService credentialService,
                           ProviderDefinitionService definitionService,
                           ProviderModelSettingsService settingsService,
                           ModelCatalogQueryService catalogQueries) {
        this.modelService = modelService;
        this.providerRegistry = providerRegistry;
        this.credentialService = credentialService;
        this.definitionService = definitionService;
        this.settingsService = settingsService;
        this.catalogQueries = catalogQueries;
    }

    // --------------------------------------------------------------- providers

    /**
     * List every provider definition visible to {@code tenantId} — Dify-parity
     * DB-driven metadata (system-scoped built-ins seeded from Java + any tenant /
     * external rows). Falls back to the Java registry when the DB hasn't been
     * seeded yet (e.g. first boot before {@code ProviderDefinitionSeeder} fires),
     * so first-run UX still works.
     */
    @GetMapping("/model-providers")
    public ApiResponse<List<Map<String, Object>>> listProviders(
            @RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(catalogQueries.providers(tenantId));
            // Cold start fallback — first boot before seeder ran, still show Java built-ins.
    }

    @GetMapping("/model-providers/{name}")
    public ApiResponse<Map<String, Object>> getProvider(@PathVariable String name,
                                                        @RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(catalogQueries.provider(name, tenantId));
    }

    // ------------------------------------------------ provider definition CRUD

    /**
     * Add a new provider definition. Powers the "从其他模块 / 手动扩展 supported
     * providers" flow: another Maven module (or an admin API caller) can register
     * a provider without shipping a Java bean, as long as {@code implementationKey}
     * matches an existing Java impl (usually {@code "openai_compatible"} —
     * reused for arbitrary OpenAI-compat endpoints).
     */
    @PostMapping("/model-provider-definitions")
    public ApiResponse<ProviderDefinitionEntity> createDefinition(
            @RequestBody Map<String, Object> body) {
        ProviderDefinitionEntity definition = ModelViewMapper.toProviderDefinition(body);
        if (definition.getSource() == null) {
            definition.setSource("custom");
        }
        return ApiResponse.ok(definitionService.upsert(definition));
    }

    @PutMapping("/model-provider-definitions/{id}")
    public ApiResponse<Void> updateDefinition(@PathVariable String id,
                                              @RequestBody Map<String, Object> patch) {
        definitionService.updatePartial(id, patch);
        return ApiResponse.ok();
    }

    @DeleteMapping("/model-provider-definitions/{id}")
    public ApiResponse<Void> deleteDefinition(@PathVariable String id) {
        definitionService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * Enumerate the Java implementation bean keys — the pool a definition's
     * {@code implementationKey} can point at. Front-end drops these into a select
     * when creating a custom definition.
     */
    @GetMapping("/model-provider-impls")
    public ApiResponse<List<String>> listImplementationKeys() {
        return ApiResponse.ok(providerRegistry.implementationKeys());
    }

    // ------------------------------------------- predefined-model catalog CRUD

    /**
     * Full catalog for a provider: predefined DB rows + custom {@code agent_model}
     * rows for this tenant, each annotated with the tenant's enable/default state
     * pulled from {@code agent_provider_model_setting} + {@code agent_tenant_default_model}.
     */
    @GetMapping("/model-providers/{name}/catalog")
    public ApiResponse<List<Map<String, Object>>> providerCatalog(
            @PathVariable String name, @RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(catalogQueries.catalog(name, tenantId));
        // Predefined DB rows first (source=predefined), then custom rows.
    }

    /** Add a predefined model to a provider (extend the shipped catalog from admin UI). */
    @PostMapping("/model-providers/{name}/predefined-models")
    public ApiResponse<PredefinedModelEntity> addPredefinedModel(
            @PathVariable String name, @RequestBody Map<String, Object> body) {
        PredefinedModelEntity row = new PredefinedModelEntity();
        row.setProviderName(name);
        row.setModel((String) body.get("model"));
        row.setLabel(body.get("label") == null ? (String) body.get("model") : (String) body.get("label"));
        row.setModelType(ModelType.of((String) body.get("modelType")));
        if (body.get("contextLength") instanceof Number n) row.setContextLength(n.intValue());
        if (body.get("dimensions") instanceof Number n) row.setDimensions(n.intValue());
        if (body.containsKey("features")) {
            row.setFeatures(io.github.aigoodle.common.util.JsonUtils.toJson(body.get("features")));
        }
        if (body.containsKey("parameterRules")) {
            row.setParameterRules(io.github.aigoodle.common.util.JsonUtils.toJson(body.get("parameterRules")));
        }
        if (body.get("sortOrder") instanceof Number n) row.setSortOrder(n.intValue());
        return ApiResponse.ok(definitionService.upsertPredefined(row));
    }

    @DeleteMapping("/model-providers/{name}/predefined-models/{id}")
    public ApiResponse<Void> deletePredefinedModel(@PathVariable String name, @PathVariable String id) {
        definitionService.deletePredefined(id);
        return ApiResponse.ok();
    }

    // ------------------------------------------- per-model enable / default

    /**
     * Dify-parity opt-in enable/disable by (provider, model, model_type). Writes
     * to {@code agent_provider_model_setting} — missing row = <b>disabled</b> per
     * user request. When enabling a model that isn't in the persisted catalog
     * (came from a live remote fetch), we also materialize an
     * {@code agent_predefined_model} row so the entry stays visible after refresh.
     * This delivers the user's requested design:
     *     "只想存启用的模型到数据库，全部就直接掉接口获取"
     */
    @PutMapping("/model-providers/{name}/models/{modelName}/enabled")
    public ApiResponse<Boolean> setModelEnabled(@PathVariable String name,
                                                @PathVariable String modelName,
                                                @RequestParam ModelType modelType,
                                                @RequestParam(required = false) String tenantId,
                                                @RequestBody Map<String, Object> body) {
        boolean enabled = body.get("enabled") instanceof Boolean b ? b
                : Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        settingsService.setEnabled(tenantId, name, modelName, modelType, enabled);
        if (enabled) {
            // Persist a lightweight predefined row (tenant-scoped) so this model
            // appears in the catalog on subsequent loads without needing a refresh.
            if (definitionService.findPredefined(name, modelName, modelType) == null) {
                PredefinedModelEntity row = new PredefinedModelEntity();
                row.setTenantId(tenantId == null || tenantId.isBlank() ? "default" : tenantId);
                row.setProviderName(name);
                row.setModel(modelName);
                row.setLabel(modelName);
                row.setModelType(modelType);
                definitionService.upsertPredefined(row);
            }
        }
        return ApiResponse.ok(enabled);
    }

    /**
     * Dify-parity set-default: at most one default per (tenant, model_type). Writes
     * to {@code agent_tenant_default_model}. Legacy {@code PUT /models/{id}/default}
     * (routes to {@code agent_model.is_default}) is kept for backward compat.
     */
    @PutMapping("/model-providers/{name}/models/{modelName}/default")
    public ApiResponse<Void> setModelDefault(@PathVariable String name,
                                             @PathVariable String modelName,
                                             @RequestParam ModelType modelType,
                                             @RequestParam(required = false) String tenantId) {
        settingsService.setDefault(tenantId, name, modelName, modelType);
        return ApiResponse.ok();
    }

    // ------------------------------------------------- provider-level credential

    @GetMapping("/model-providers/{name}/credential")
    public ApiResponse<Map<String, Object>> getProviderCredential(@PathVariable String name,
                                                                  @RequestParam(required = false) String tenantId) {
        ModelProvider provider = providerRegistry.get(name);
        ProviderCredentialEntity credential = credentialService.findPrimary(tenantId, provider.getName());
        return ApiResponse.ok(ModelViewMapper.toCredentialView(provider, credential));
    }

    /**
     * Save (or rotate) the tenant's primary credential for {@code name}. When the
     * provider supports remote listing, the vendor is called BEFORE the credential is
     * persisted — a bad api key throws {@code 400} and no state changes. On success the
     * catalog is seeded as {@code agent_model} rows with {@code enabled=false}, so the
     * UI can immediately show every available model and let the user toggle switches.
     */
    @PutMapping("/model-providers/{name}/credential")
    public ApiResponse<Map<String, Object>> upsertProviderCredential(
            @PathVariable String name, @RequestBody ProviderCredentialRequest request) {
        ModelProvider provider = providerRegistry.get(name);
        ProviderCredentialEntity credential = modelService.saveProviderCredentialWithValidation(
                request.getTenantId(), provider.getName(), request.getCredentials());
        return ApiResponse.ok(ModelViewMapper.toCredentialView(provider, credential));
    }

    /**
     * Delete the credential AND cascade every tenant-scoped row (enable
     * toggles, tenant defaults, materialized catalog entries, custom
     * {@code agent_model} rows) so re-adding the API key lands on a clean
     * slate. Without the cascade the previous enable / default state
     * resurfaces the moment the credential is saved again — the user report
     * this fix addresses.
     */
    @DeleteMapping("/model-providers/{name}/credential")
    public ApiResponse<Void> deleteProviderCredential(@PathVariable String name,
                                                      @RequestParam(required = false) String tenantId) {
        modelService.deleteProviderCredentialCascade(
                tenantId, providerRegistry.get(name).getName());
        return ApiResponse.ok();
    }

    /**
     * Ask the vendor "what models does this key unlock?" using the tenant's saved
     * credential. Non-destructive — does not touch the catalog rows.
     */
    @GetMapping("/model-providers/{name}/remote-models")
    public ApiResponse<List<Map<String, Object>>> listRemoteModels(@PathVariable String name,
                                                                    @RequestParam(required = false) String tenantId) {
        List<RemoteModel> models = modelService.listRemoteModels(tenantId, name);
        return ApiResponse.ok(models.stream().map(ModelViewMapper::toRemoteModelView).toList());
    }

    /**
     * "重新拉取": hit the vendor and return the raw list — transient, no DB writes.
     * The UI merges these with the persisted predefined catalog for display; only
     * models the user explicitly enables end up in {@code agent_provider_model_setting}.
     */
    @PostMapping("/model-providers/{name}/refresh-catalog")
    public ApiResponse<List<Map<String, Object>>> refreshCatalog(
            @PathVariable String name, @RequestParam(required = false) String tenantId) {
        List<RemoteModel> remote = modelService.refreshCatalog(tenantId, name);
        return ApiResponse.ok(remote.stream().map(ModelViewMapper::toRemoteModelView).toList());
    }

    // ------------------------------------------------------------------ models

    @GetMapping("/models")
    public ApiResponse<List<ModelEntity>> listModels(@RequestParam(required = false) String tenantId,
                                                     @RequestParam(required = false) ModelType type) {
        return ApiResponse.ok(type == null ? modelService.list(tenantId) : modelService.listByType(tenantId, type));
    }

    @GetMapping("/models/{id}")
    public ApiResponse<ModelEntity> getModel(@PathVariable String id) {
        return ApiResponse.ok(modelService.require(id));
    }

    @PostMapping("/models")
    public ApiResponse<ModelEntity> registerModel(@RequestBody ModelRegistration registration) {
        return ApiResponse.ok(modelService.register(registration));
    }

    @PutMapping("/models/{id}/credentials")
    public ApiResponse<ModelEntity> updateCredentials(@PathVariable String id,
                                                      @RequestBody Map<String, Object> credentials) {
        return ApiResponse.ok(modelService.updateCredentials(id, credentials));
    }

    @PutMapping("/models/{id}/default")
    public ApiResponse<Void> markDefault(@PathVariable String id) {
        modelService.setDefault(id);
        return ApiResponse.ok();
    }

    /**
     * "开关": flip the {@code enabled} column. Disabled rows stay in the DB (metadata
     * preserved across catalog refreshes) but are filtered out of every downstream
     * chat/embedding lookup, so this is the "启用/禁用" the settings UI drives.
     */
    @PatchMapping("/models/{id}/enabled")
    public ApiResponse<ModelEntity> setEnabled(@PathVariable String id,
                                               @RequestBody Map<String, Object> body) {
        Object v = body.get("enabled");
        boolean enabled = v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
        return ApiResponse.ok(modelService.setEnabled(id, enabled));
    }

    @DeleteMapping("/models/{id}")
    public ApiResponse<Void> deleteModel(@PathVariable String id) {
        modelService.delete(id);
        return ApiResponse.ok();
    }

    /** Dry-run the credentials without persisting: builds the provider once. */
    @PostMapping("/models/validate")
    public ApiResponse<Void> validate(@RequestBody ModelRegistration registration) {
        modelService.validate(registration);
        return ApiResponse.ok();
    }

    /**
     * Live-hit an already-saved model. Returns {ok, latencyMs, kind, snippet|dimensions|error}.
     * The UI wires this into a "测试连接" button so operators can verify a key still works
     * after rotation without redeploying.
     */
    @PostMapping("/models/{id}/test")
    public ApiResponse<Map<String, Object>> testConnection(@PathVariable String id) {
        return ApiResponse.ok(modelService.testConnection(id));
    }

    // -------------------------------------------------------- model parameters

    /**
     * Read the per-model parameter values + the rule set the UI should render.
     * Returns the whole payload the parameter drawer needs in one round-trip.
     * Filters out entries whose key does not match a rule name so credential
     * overrides ({@code apiKey}/{@code baseUrl}) don't leak into the view.
     */
    @GetMapping("/models/{id}/parameters")
    public ApiResponse<Map<String, Object>> getModelParameters(@PathVariable String id) {
        return ApiResponse.ok(catalogQueries.parameters(id));
    }

    /**
     * Save a partial parameter update. Payload {@code {temperature: 0.9}} merges
     * into whatever is stored; {@code {temperature: null}} removes the override so
     * the provider falls back to its own default.
     */
    @PutMapping("/models/{id}/parameters")
    public ApiResponse<Map<String, Object>> updateModelParameters(@PathVariable String id,
                                                                  @RequestBody Map<String, Object> parameters) {
        modelService.updateParameters(id, parameters);
        return getModelParameters(id);
    }

    /**
     * The tenant's default model per {@link ModelType} — feeds the "系统默认模型"
     * summary section so LLM / EMBEDDING / RERANK / TTS defaults are visible in a
     * single glance instead of scattered across every model row.
     */
    @GetMapping("/models/defaults")
    public ApiResponse<Map<String, ModelEntity>> listDefaults(
            @RequestParam(required = false) String tenantId) {
        Map<String, ModelEntity> out = new LinkedHashMap<>();
        modelService.listDefaults(tenantId).forEach((type, entity) -> out.put(type.name(), entity));
        return ApiResponse.ok(out);
    }

    /**
     * Enabled models grouped by {@link ModelType}, then by provider — the shape
     * the "系统默认模型" panel binds its per-type &lt;a-select&gt; against
     * (opt-groups per provider, options are individual models). Only providers
     * with saved credentials + enabled models participate; disabled entries are
     * filtered out so the dropdown only offers actually-usable candidates.
     *
     * <p>Response shape:
     * <pre>{
     *   "LLM": [{
     *     "id": "openai",
     *     "provider": "openai",
     *     "description": "...",
     *     "declaration": { "icon": "openai-svg" },
     *     "modelList": [{
     *       "id": "openai::gpt-4o::LLM",
     *       "modelName": "gpt-4o",
     *       "modelType": "LLM",
     *       "providerName": "openai"
     *     }, ...]
     *   }, ...],
     *   "TEXT_EMBEDDING": [...], ...
     * }</pre>
     * Model {@code id} is a composite key ({@code provider::model::type}) so the
     * frontend can round-trip it straight to
     * {@code PUT /model-providers/{name}/models/{modelName}/default}.
     */
    @GetMapping("/models/grouped-by-type")
    public ApiResponse<Map<String, List<Map<String, Object>>>> listModelsGroupedByType(
            @RequestParam(required = false) String tenantId) {
        Map<String, List<Map<String, Object>>> providersByModelType = new LinkedHashMap<>();
        // Seed empty buckets so the UI can render every supported type even when
        // there are no candidates yet (keeps the shape stable).
        for (ModelType modelType : ModelType.values()) {
            providersByModelType.put(modelType.name(), new ArrayList<>());
        }

        for (ProviderDefinitionEntity definition : definitionService.list(tenantId)) {
            // A provider only surfaces in the default picker once its credential
            // has been saved — the model behind it wouldn't be invocable otherwise.
            // We intentionally do NOT require the per-model "enabled" toggle here:
            // the default picker's job is "which model do I use by default",
            // which is a strictly larger question than the catalog's opt-in enable
            // gate. Requiring both would force users to click "enable" in the
            // popover before they can even pick a default — which is what the
            // user hit ("配置了模型之后…下拉选择默认模型无法加载"). Downstream
            // usability is guaranteed by {@link #setModelDefault} which flips
            // the enabled flag as a side effect of choosing a default.
            ProviderCredentialEntity credential =
                    credentialService.findPrimary(tenantId, definition.getName());
            if (credential == null) {
                continue;
            }

            // Bucket every predefined model (shipped by the provider) by type.
            Map<ModelType, List<Map<String, Object>>> modelsByType = new LinkedHashMap<>();
            for (PredefinedModelEntity predefinedModel
                    : definitionService.listPredefined(definition.getName())) {
                modelsByType.computeIfAbsent(
                                predefinedModel.getModelType(), ignored -> new ArrayList<>())
                        .add(ModelViewMapper.toGroupedModelView(
                                definition.getName(), predefinedModel.getModel(),
                                predefinedModel.getModelType()));
            }
            // Bucket every custom-registered model too — user-added rows are
            // implicitly "configured" by virtue of existing in agent_model.
            for (ModelEntity customModel
                    : modelService.listByProvider(tenantId, definition.getName())) {
                modelsByType.computeIfAbsent(
                                customModel.getModelType(), ignored -> new ArrayList<>())
                        .add(ModelViewMapper.toGroupedModelView(
                                definition.getName(), customModel.getModelName(),
                                customModel.getModelType()));
            }

            // Publish one provider entry per model type with a non-empty bucket.
            for (Map.Entry<ModelType, List<Map<String, Object>>> models : modelsByType.entrySet()) {
                providersByModelType.get(models.getKey().name()).add(
                        ModelViewMapper.toGroupedProviderView(definition, models.getValue()));
            }
        }
        return ApiResponse.ok(providersByModelType);
    }
}
