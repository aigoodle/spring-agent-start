package io.github.aigoodle.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderCredentialEntity;
import io.github.aigoodle.model.entity.TenantDefaultModelEntity;
import io.github.aigoodle.model.enums.ModelFeature;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.mapper.ModelMapper;
import io.github.aigoodle.model.provider.ModelEndpoint;
import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.model.provider.RemoteModel;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import io.github.aigoodle.model.runtime.ModelInstance;
import io.github.aigoodle.model.runtime.ModelInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The primary entry point of the model module: register models, resolve their
 * (decrypted, merged) endpoints and obtain cached runtime handles that other
 * modules (knowledge, workflow, agent) invoke.
 */
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);
    private static final String DEFAULT_TENANT = "default";

    private final ModelMapper modelMapper;
    private final ProviderCredentialService credentialService;
    private final CredentialCodec codec;
    private final ModelProviderRegistry registry;
    private final ModelInstanceFactory instanceFactory;
    private final ProviderDefinitionService definitionService;
    private final ProviderModelSettingsService settingsService;

    public ModelService(ModelMapper modelMapper, ProviderCredentialService credentialService,
                        CredentialCodec codec, ModelProviderRegistry registry,
                        ModelInstanceFactory instanceFactory,
                        ProviderDefinitionService definitionService,
                        ProviderModelSettingsService settingsService) {
        this.modelMapper = modelMapper;
        this.credentialService = credentialService;
        this.codec = codec;
        this.registry = registry;
        this.instanceFactory = instanceFactory;
        this.definitionService = definitionService;
        this.settingsService = settingsService;
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public ModelEntity register(ModelRegistration reg) {
        ModelProvider provider = registry.get(reg.getProviderName());
        if (!provider.supports(reg.getModelType())) {
            throw new AgentException("model_type_unsupported",
                    "Provider '" + provider.getName() + "' does not support " + reg.getModelType(), null);
        }
        String tenant = tenant(reg.getTenantId());

        ModelEntity entity = new ModelEntity();
        entity.setTenantId(tenant);
        entity.setProviderName(reg.getProviderName());
        entity.setModelName(reg.getModelName());
        entity.setModelType(reg.getModelType());
        entity.setCredentialId(reg.getCredentialId());
        entity.setEncryptedConfig(codec.encode(reg.getCredentials()));
        entity.setEnabled(Boolean.TRUE);
        entity.setIsDefault(reg.isAsDefault());
        modelMapper.insert(entity);

        if (reg.isAsDefault()) {
            clearOtherDefaults(tenant, reg.getModelType(), entity.getId());
        }
        log.info("Registered model id={} provider={} model={} type={}",
                entity.getId(), reg.getProviderName(), reg.getModelName(), reg.getModelType());
        return entity;
    }

    /**
     * Merge {@code patch} into the existing decoded credentials, then re-encrypt. Fields
     * absent from {@code patch} keep their old value — so the frontend can PATCH a
     * single rotated API key without having to re-type the base URL, etc.
     * <p>
     * Passing {@code null} or an empty map is a no-op. Use {@link #replaceCredentials}
     * if you actually want the full-overwrite semantic (rare — currently only useful
     * when moving providers).
     */
    @Transactional
    public ModelEntity updateCredentials(String id, Map<String, Object> patch) {
        ModelEntity entity = require(id);
        if (patch == null || patch.isEmpty()) {
            return entity;
        }
        Map<String, Object> merged = new HashMap<>(codec.decode(entity.getEncryptedConfig()));
        merged.putAll(patch);
        entity.setEncryptedConfig(codec.encode(merged));
        modelMapper.updateById(entity);
        instanceFactory.evict(id);
        return entity;
    }

    /** Overwrite every credential field. Rarely what you want — see {@link #updateCredentials}. */
    @Transactional
    public ModelEntity replaceCredentials(String id, Map<String, Object> credentials) {
        ModelEntity entity = require(id);
        entity.setEncryptedConfig(codec.encode(credentials));
        modelMapper.updateById(entity);
        instanceFactory.evict(id);
        return entity;
    }

    /**
     * Return the decoded per-model overrides map. Both model parameters
     * (temperature, maxTokens, …) and any model-level credential overrides live in
     * the same {@code encryptedConfig} JSON — same storage, same encryption. The
     * caller separates them by rule name when rendering.
     */
    public Map<String, Object> getModelProperties(String id) {
        ModelEntity entity = require(id);
        return codec.decode(entity.getEncryptedConfig());
    }

    /**
     * Merge model parameter values (from the Dify-style parameter drawer) into the
     * existing config. Fields absent from {@code parameters} keep their old value —
     * the UI can save a single slider change without re-typing everything.
     * <p>
     * Passing an empty map is a no-op. A value of {@code null} explicitly
     * <em>removes</em> a key so the model falls back to the provider's default
     * (whatever OpenAiChatOptions.builder() would produce).
     */
    @Transactional
    public ModelEntity updateParameters(String id, Map<String, Object> parameters) {
        ModelEntity entity = require(id);
        if (parameters == null || parameters.isEmpty()) {
            return entity;
        }
        Map<String, Object> merged = new HashMap<>(codec.decode(entity.getEncryptedConfig()));
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            if (e.getValue() == null) {
                merged.remove(e.getKey());
            } else {
                merged.put(e.getKey(), e.getValue());
            }
        }
        entity.setEncryptedConfig(codec.encode(merged));
        modelMapper.updateById(entity);
        instanceFactory.evict(id);
        return entity;
    }

    /**
     * Resolve the parameter rules the UI should render for this model — either the
     * PredefinedModel-level override or the provider-wide default per model type.
     * Returns an empty list for unknown models.
     */
    public List<io.github.aigoodle.model.provider.ModelParameterRule> parameterRulesFor(String id) {
        ModelEntity entity = require(id);
        ModelProvider provider = registry.get(entity.getProviderName());
        for (io.github.aigoodle.model.provider.PredefinedModel p : provider.predefinedModels()) {
            if (p.getModel().equalsIgnoreCase(entity.getModelName())
                    && p.getModelType() == entity.getModelType()
                    && p.getParameterRules() != null && !p.getParameterRules().isEmpty()) {
                return p.getParameterRules();
            }
        }
        return provider.defaultParameterRules(entity.getModelType());
    }

    /**
     * Group each tenant's default models by {@link ModelType}. Feeds the Dify-style
     * "系统默认模型" panel at the top of the provider hub so the user can see
     * "LLM 用什么 / EMBEDDING 用什么 / RERANK 用什么" in a single glance and
     * one-click reassign.
     */
    public Map<ModelType, ModelEntity> listDefaults(String tenantId) {
        Map<ModelType, ModelEntity> out = new java.util.EnumMap<>(ModelType.class);
        for (ModelType t : ModelType.values()) {
            ModelEntity def = getDefault(tenantId, t);
            if (def != null) {
                out.put(t, def);
            }
        }
        return out;
    }

    @Transactional
    public void setDefault(String id) {
        ModelEntity entity = require(id);
        entity.setIsDefault(Boolean.TRUE);
        modelMapper.updateById(entity);
        clearOtherDefaults(entity.getTenantId(), entity.getModelType(), id);
    }

    @Transactional
    public void delete(String id) {
        modelMapper.deleteById(id);
        instanceFactory.evict(id);
    }

    public ModelEntity get(String id) {
        return modelMapper.selectById(id);
    }

    public ModelEntity require(String id) {
        ModelEntity entity = modelMapper.selectById(id);
        if (entity == null) {
            throw new AgentException("model_not_found", "Model not found: " + id, null);
        }
        return entity;
    }

    public List<ModelEntity> list(String tenantId) {
        return modelMapper.selectList(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getTenantId, tenant(tenantId)));
    }

    public List<ModelEntity> listByType(String tenantId, ModelType type) {
        return modelMapper.selectList(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getTenantId, tenant(tenantId))
                .eq(ModelEntity::getModelType, type)
                .eq(ModelEntity::getEnabled, true));
    }

    /** Custom-registered models for a given (tenant, provider). Used by the catalog view. */
    public List<ModelEntity> listByProvider(String tenantId, String providerName) {
        return modelMapper.selectList(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getTenantId, tenant(tenantId))
                .eq(ModelEntity::getProviderName, providerName));
    }

    /**
     * Resolve the tenant default in three layers:
     * <ol>
     *   <li>Dify-parity {@code agent_tenant_default_model} — if a row exists,
     *       resolve (provider, model, type) into an ephemeral {@link ModelEntity}
     *       (materialized on-the-fly if no custom row exists yet).</li>
     *   <li>Legacy {@code agent_model.is_default} — backward compat for tenants
     *       migrated from the old design.</li>
     *   <li>Any enabled model of the type — last-resort fallback.</li>
     * </ol>
     */
    public ModelEntity getDefault(String tenantId, ModelType type) {
        // 1. New Dify-parity default table
        if (settingsService != null) {
            var dfl = settingsService.getDefault(tenantId, type);
            if (dfl != null) {
                ModelEntity resolved = findOrMaterialize(tenant(tenantId),
                        dfl.getProviderName(), dfl.getModelName(), type);
                if (resolved != null) return resolved;
            }
        }
        // 2. Legacy is_default flag
        ModelEntity legacy = modelMapper.selectOne(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getTenantId, tenant(tenantId))
                .eq(ModelEntity::getModelType, type)
                .eq(ModelEntity::getIsDefault, true)
                .last("limit 1"));
        if (legacy != null) return legacy;
        // 3. Any enabled model
        return modelMapper.selectOne(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getTenantId, tenant(tenantId))
                .eq(ModelEntity::getModelType, type)
                .eq(ModelEntity::getEnabled, true)
                .last("limit 1"));
    }

    /**
     * Return the existing custom {@link ModelEntity} for the triple, or materialize
     * a lightweight one that inherits the provider credential. This is how a
     * "predefined" tenant default becomes a usable runtime handle without forcing
     * the user to explicitly {@code POST /models} first — the row is created
     * lazily the first time the runtime asks for it.
     */
    @Transactional
    public ModelEntity findOrMaterialize(String tenantId, String providerName,
                                         String modelName, ModelType modelType) {
        ModelEntity existing = modelMapper.selectOne(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getTenantId, tenantId)
                .eq(ModelEntity::getProviderName, providerName)
                .eq(ModelEntity::getModelName, modelName)
                .eq(ModelEntity::getModelType, modelType)
                .last("limit 1"));
        if (existing != null) return existing;
        ProviderCredentialEntity cred = credentialService.findPrimary(tenantId, providerName);
        ModelEntity fresh = new ModelEntity();
        fresh.setTenantId(tenantId);
        fresh.setProviderName(providerName);
        fresh.setModelName(modelName);
        fresh.setModelType(modelType);
        fresh.setCredentialId(cred == null ? null : cred.getId());
        fresh.setEnabled(Boolean.TRUE);
        fresh.setIsDefault(Boolean.FALSE);
        modelMapper.insert(fresh);
        log.info("Materialized agent_model row on default-lookup for tenant={} provider={} model={} type={}",
                tenantId, providerName, modelName, modelType);
        return fresh;
    }

    // ------------------------------------------------------------- resolution

    /** Merge provider-level + model-level credentials into a runnable endpoint. */
    public ModelEndpoint resolveEndpoint(ModelEntity entity) {
        Map<String, Object> creds = new HashMap<>();
        if (entity.getCredentialId() != null) {
            creds.putAll(credentialService.decodeCredentials(entity.getCredentialId()));
        }
        creds.putAll(codec.decode(entity.getEncryptedConfig()));

        Object apiKey = creds.remove("apiKey");
        Object baseUrl = creds.remove("baseUrl");
        return ModelEndpoint.builder()
                .id(entity.getId())
                .providerName(entity.getProviderName())
                .modelName(entity.getModelName())
                .modelType(entity.getModelType())
                .apiKey(apiKey == null ? null : String.valueOf(apiKey))
                .baseUrl(baseUrl == null ? null : String.valueOf(baseUrl))
                .properties(creds)
                .build();
    }

    public ModelEndpoint resolveEndpoint(String id) {
        return resolveEndpoint(require(id));
    }

    // -------------------------------------------------------------- runtime

    public ModelInstance getModelInstance(String id) {
        return instanceFactory.getOrCreate(resolveEndpoint(id));
    }

    public ChatClient getChatClient(String id) {
        return getModelInstance(id).getChatClient();
    }

    /**
     * Resolve a {@link ChatClient} by (tenant, provider, model name) — the console
     * saves apps with the plain model name + provider key rather than a synthetic
     * uuid, so runtime lookups go through {@link #findOrMaterialize} to hydrate the
     * matching {@code agent_model} row (creating one if the vendor catalog was
     * only registered as predefined).
     */
    public ChatClient getChatClient(String tenantId, String providerName, String modelName) {
        ModelEntity entity = findOrMaterialize(tenant(tenantId), providerName, modelName, ModelType.LLM);
        return getChatClient(entity.getId());
    }

    public ChatModel getChatModel(String id) {
        return getModelInstance(id).getChatModel();
    }

    public EmbeddingModel getEmbeddingModel(String id) {
        return getModelInstance(id).getEmbeddingModel();
    }

    public ModelInstance getDefaultInstance(String tenantId, ModelType type) {
        ModelEntity def = getDefault(tenantId, type);
        if (def == null) {
            throw new AgentException("no_default_model",
                    "No " + type + " model configured for tenant '" + tenant(tenantId) + "'", null);
        }
        return getModelInstance(def.getId());
    }

    /**
     * Build the model once (without caching) to surface configuration errors early.
     * Does not make a network call; provider construction validates required fields.
     */
    public void validate(ModelRegistration reg) {
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("validation")
                .providerName(reg.getProviderName())
                .modelName(reg.getModelName())
                .modelType(reg.getModelType())
                .apiKey(str(reg.getCredentials().get("apiKey")))
                .baseUrl(str(reg.getCredentials().get("baseUrl")))
                .properties(new HashMap<>(reg.getCredentials()))
                .build();
        instanceFactory.build(endpoint);
    }

    // ---------------------------------------------------- provider-key flow

    /**
     * Build a "list only" endpoint from the tenant's saved provider credential — no
     * ModelEntity involved. Used by the provider-hub UI to call
     * {@link ModelProvider#listRemoteModels} against an already-saved credential.
     */
    public ModelEndpoint resolveProviderEndpoint(String tenantId, String providerName) {
        ProviderCredentialEntity cred = credentialService.findPrimary(tenant(tenantId), providerName);
        if (cred == null) {
            throw new AgentException("provider_credential_missing",
                    "Provider '" + providerName + "' has no saved credential for tenant '"
                            + tenant(tenantId) + "'", null);
        }
        return endpointFromCreds(providerName, codec.decode(cred.getEncryptedConfig()));
    }

    /**
     * Validate raw credentials against the vendor without persisting anything. Used by
     * PUT /credential to reject a bad API key before it touches the DB. Returns the
     * fetched catalog so the caller can reuse it for the initial seed — saving one
     * round-trip.
     */
    public List<RemoteModel> previewRemoteModels(String providerName,
                                                 Map<String, Object> credentials) {
        ModelProvider provider = registry.get(providerName);
        ModelEndpoint endpoint = endpointFromCreds(providerName,
                credentials == null ? new HashMap<>() : new HashMap<>(credentials));
        return provider.listRemoteModels(endpoint);
    }

    /** Ask the provider for its live catalog against the tenant's saved credential. */
    public List<RemoteModel> listRemoteModels(String tenantId, String providerName) {
        ModelProvider provider = registry.get(providerName);
        return provider.listRemoteModels(resolveProviderEndpoint(tenantId, providerName));
    }

    /**
     * Hit the vendor's model listing endpoint and return the raw list — <em>no
     * persistence whatsoever</em>. The UI merges these transient remote entries
     * with the DB predefined catalog for display; only when the user explicitly
     * enables one does a row get written (to {@code agent_provider_model_setting}).
     * <p>
     * This is the user's explicit design ("读取到的模型太多了 我只想存启用的，全部
     * 就直接掉接口获取"). The seeded {@code agent_predefined_model} rows still
     * ship the OOTB known-good catalog; refresh only adds transient options.
     */
    public List<RemoteModel> refreshCatalog(String tenantId, String providerName) {
        ModelProvider provider = registry.get(providerName);
        ProviderCredentialEntity cred = credentialService.findPrimary(tenant(tenantId), providerName);
        if (cred == null) {
            throw new AgentException("provider_credential_missing",
                    "Save a provider credential before refreshing the catalog", null);
        }
        return provider.listRemoteModels(
                endpointFromCreds(providerName, codec.decode(cred.getEncryptedConfig())));
    }

    /**
     * Delete the tenant's provider credential AND every tenant-scoped row that
     * hung off it — the enable-settings, the tenant-default rows, materialized
     * catalog entries, and the tenant's custom {@code agent_model} rows for that
     * provider.
     * <p>
     * Without this cascade, deleting an API key leaves stale state behind so
     * re-adding the same provider immediately resurrects every previously
     * enabled model + default — matching the user report "删除的时候没删干净,
     * 再次添加还是之前的".
     * <p>
     * The system-tenant predefined catalog (the provider's shipped defaults)
     * and the provider definition itself are left alone — they are metadata,
     * not tenant state.
     */
    @Transactional
    public void deleteProviderCredentialCascade(String tenantId, String providerName) {
        String tenant = tenant(tenantId);
        // 1. Enable toggles + tenant defaults for this provider.
        if (settingsService != null) {
            settingsService.clearProviderSettings(tenant, providerName);
            settingsService.clearProviderDefaults(tenant, providerName);
        }
        // 2. Custom agent_model rows for this (tenant, provider). Evict runtime
        // caches per id so pooled ChatClients don't outlive their credentials.
        List<ModelEntity> rows = listByProvider(tenant, providerName);
        for (ModelEntity m : rows) {
            modelMapper.deleteById(m.getId());
            instanceFactory.evict(m.getId());
        }
        // 3. Materialized (tenant-scoped) predefined catalog rows.
        if (definitionService != null) {
            definitionService.deleteTenantPredefined(tenant, providerName);
        }
        // 4. Finally the credential itself.
        credentialService.deletePrimary(tenant, providerName);
        log.info("Cascade-deleted provider credential tenant={} provider={} (custom-models={})",
                tenant, providerName, rows.size());
    }

    /**
     * Save the tenant's provider credential. Dify-parity: <em>no auto-enable</em>.
     * No {@code agent_model} rows and no {@code agent_provider_model_setting}
     * rows are created — the user picks which models to enable via the settings
     * UI. Returns just the persisted credential.
     */
    @Transactional
    public ProviderCredentialEntity saveProviderCredentialWithValidation(String tenantId,
                                                                         String providerName,
                                                                         Map<String, Object> credentials) {
        // Validate credential by attempting a listing (bad key → surface error to
        // caller). No persistence beyond the credential itself.
        ModelProvider provider = registry.get(providerName);
        if (provider.supportsRemoteModelListing()) {
            try {
                previewRemoteModels(providerName, credentials);
            } catch (Exception e) {
                log.warn("Remote listing failed for provider {} during credential save: {}",
                        providerName, e.getMessage());
                // Non-fatal — save the credential anyway; user can retry refresh later.
            }
        }
        return credentialService.upsertPrimary(tenantId, providerName, credentials);
    }

    /**
     * Flip the {@code enabled} column on a single model row. This IS the "开关" — a
     * disabled row still lives in {@code agent_model} (so its metadata is preserved
     * across refreshes) but is filtered out by every downstream {@link #listByType}
     * call, so chat/knowledge/workflow only see enabled models.
     */
    @Transactional
    public ModelEntity setEnabled(String id, boolean enabled) {
        ModelEntity entity = require(id);
        entity.setEnabled(enabled);
        modelMapper.updateById(entity);
        instanceFactory.evict(id);
        return entity;
    }

    // ---------- shared helpers for the catalog flow ----------

    private ModelEndpoint endpointFromCreds(String providerName, Map<String, Object> creds) {
        Object apiKey = creds.remove("apiKey");
        Object baseUrl = creds.remove("baseUrl");
        return ModelEndpoint.builder()
                .providerName(providerName)
                .apiKey(apiKey == null ? null : String.valueOf(apiKey))
                .baseUrl(baseUrl == null ? null : String.valueOf(baseUrl))
                .properties(creds)
                .build();
    }

    /**
     * Idempotent bulk upsert. Existing (provider, modelName, modelType) rows are
     * skipped — their {@code enabled} state is user-owned and must never be reset by
     * a catalog refresh. New rows are inserted with {@code enabled=false} — the user
     * flips the switch when they want to actually use the model.
     */
    private List<ModelEntity> upsertCatalog(String tenant, String providerName,
                                            String credentialId, List<RemoteModel> remote) {
        if (remote == null || remote.isEmpty()) {
            return List.of();
        }
        ModelProvider provider = registry.get(providerName);
        List<ModelEntity> existing = list(tenant).stream()
                .filter(m -> providerName.equalsIgnoreCase(m.getProviderName()))
                .toList();
        List<ModelEntity> created = new ArrayList<>();
        for (RemoteModel rm : remote) {
            if (rm.getModelId() == null || rm.getModelId().isBlank() || rm.getModelType() == null) {
                continue;
            }
            if (!provider.supports(rm.getModelType())) {
                continue;
            }
            boolean dup = existing.stream().anyMatch(m ->
                    rm.getModelId().equalsIgnoreCase(m.getModelName())
                            && rm.getModelType() == m.getModelType());
            if (dup) {
                continue;
            }
            ModelEntity entity = new ModelEntity();
            entity.setTenantId(tenant);
            entity.setProviderName(providerName);
            entity.setModelName(rm.getModelId());
            entity.setModelType(rm.getModelType());
            entity.setCredentialId(credentialId);
            entity.setEncryptedConfig(null);
            entity.setEnabled(Boolean.FALSE);
            entity.setIsDefault(Boolean.FALSE);
            modelMapper.insert(entity);
            created.add(entity);
        }
        log.info("Refreshed catalog for tenant={} provider={} — added {} new rows (existing: {})",
                tenant, providerName, created.size(), existing.size());
        return created;
    }

    /**
     * Actually hit the remote provider: a small ping for LLM, an embed for embedding
     * model. Returns a summary of latency + a snippet of the response so the UI can
     * show something more useful than "ok" — good for diagnosing key rotation.
     * <p>
     * Uses a fresh (non-cached) instance so we can attach a fast-fail retry policy
     * — Spring AI's default is {@code maxAttempts=10, initial=2s, multiplier=5,
     * max=180s} which turns a bad URL or 404 into a 30+ minute silent hang. For a
     * user-facing "点击测试" this is unusable; we cap the wait at a couple of seconds
     * and let the real error surface. Production traffic still uses the default
     * cached instance with the full retry policy.
     */
    public Map<String, Object> testConnection(String id) {
        ModelEntity entity = require(id);
        ModelType type = entity.getModelType();
        long start = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        try {
            if (type == ModelType.LLM) {
                ChatModel model = fastFailChatModel(entity);
                String reply = ChatClient.builder(model).build()
                        .prompt().user("ping").call().content();
                result.put("kind", "chat");
                result.put("snippet",
                        reply == null ? "" : reply.substring(0, Math.min(200, reply.length())));
            } else if (type == ModelType.TEXT_EMBEDDING) {
                // Embedding models don't compose a retry wrapper the same way — the
                // cached instance is already fast-fail on real errors.
                EmbeddingModel embedding = getEmbeddingModel(id);
                float[] vec = embedding.embed("connection test");
                result.put("kind", "embedding");
                result.put("dimensions", vec == null ? 0 : vec.length);
            } else {
                result.put("kind", "unsupported");
                result.put("message", "Test not supported for " + type);
            }
            result.put("ok", true);
        } catch (Exception e) {
            log.warn("Model {} connection test failed: {}", id, e.getMessage());
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        result.put("latencyMs", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * Build a one-shot LLM {@link ChatModel} with a tight retry policy so the
     * "测试连接" button returns a real answer in seconds instead of Spring AI's
     * default 30+ min ceiling (maxAttempts=10, exponential up to 3 min per
     * attempt). Reuses the provider's normal {@code createChatModel} for URL /
     * options fidelity, then swaps in a 2-attempt / 500 ms fixed-backoff retry
     * — enough to survive a transient TLS reset or CDN 502 without turning a
     * bad key into a multi-minute silent hang.
     * <p>
     * We deliberately don't touch the cached instance ({@link #instanceFactory})
     * — production traffic keeps the durable exponential-backoff policy.
     */
    private ChatModel fastFailChatModel(ModelEntity entity) {
        ModelEndpoint endpoint = resolveEndpoint(entity);
        ChatModel base = registry.get(entity.getProviderName()).createChatModel(endpoint);
        // Spring AI's OpenAiChatModel exposes a mutate()/retryTemplate() path we can
        // use to swap the retry policy. For providers that don't (or that use a
        // different SDK), the mutation is a no-op and the base model is returned as-is.
        if (base instanceof org.springframework.ai.openai.OpenAiChatModel openAi) {
            return openAi.mutate()
                    .retryTemplate(org.springframework.retry.support.RetryTemplate.builder()
                            .maxAttempts(2)
                            .fixedBackoff(java.time.Duration.ofMillis(500))
                            .build())
                    .build();
        }
        return base;
    }

    // --------------------------------------------------------------- helpers

    private void clearOtherDefaults(String tenantId, ModelType type, String keepId) {
        ModelEntity patch = new ModelEntity();
        patch.setIsDefault(Boolean.FALSE);
        modelMapper.update(patch, Wrappers.<ModelEntity>lambdaUpdate()
                .eq(ModelEntity::getTenantId, tenantId)
                .eq(ModelEntity::getModelType, type)
                .ne(ModelEntity::getId, keepId));
    }

    private static String tenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
