package io.github.aigoodle.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.model.entity.ProviderCredentialEntity;
import io.github.aigoodle.model.mapper.ProviderCredentialMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD for provider-level shared credentials. Secrets are encrypted via
 * {@link CredentialCodec} before they ever touch the database.
 */
public class ProviderCredentialService {

    private final ProviderCredentialMapper mapper;
    private final CredentialCodec codec;

    public ProviderCredentialService(ProviderCredentialMapper mapper, CredentialCodec codec) {
        this.mapper = mapper;
        this.codec = codec;
    }

    @Transactional
    public ProviderCredentialEntity save(String tenantId, String providerName, String credentialName,
                                         Map<String, Object> credentials) {
        ProviderCredentialEntity entity = new ProviderCredentialEntity();
        entity.setTenantId(tenantId == null ? "default" : tenantId);
        entity.setProviderName(providerName);
        entity.setCredentialName(credentialName == null ? providerName : credentialName);
        entity.setEncryptedConfig(codec.encode(credentials));
        entity.setEnabled(Boolean.TRUE);
        mapper.insert(entity);
        return entity;
    }

    @Transactional
    public void update(String id, Map<String, Object> credentials) {
        ProviderCredentialEntity entity = require(id);
        entity.setEncryptedConfig(codec.encode(credentials));
        mapper.updateById(entity);
    }

    public ProviderCredentialEntity get(String id) {
        return mapper.selectById(id);
    }

    public ProviderCredentialEntity require(String id) {
        ProviderCredentialEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new AgentException("credential_not_found", "Provider credential not found: " + id, null);
        }
        return entity;
    }

    public Map<String, Object> decodeCredentials(String id) {
        return codec.decode(require(id).getEncryptedConfig());
    }

    public List<ProviderCredentialEntity> listByProvider(String tenantId, String providerName) {
        return mapper.selectList(new LambdaQueryWrapper<ProviderCredentialEntity>()
                .eq(ProviderCredentialEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .eq(ProviderCredentialEntity::getProviderName, providerName));
    }

    @Transactional
    public void delete(String id) {
        mapper.deleteById(id);
    }

    // ------------------------------------------------------- primary credential

    /**
     * Return the tenant's single primary credential for {@code providerName}, or null
     * if none exists. "Primary" is the first row for the (tenant, provider) pair — used
     * by the Dify-parity "one key per provider" flow the model settings UI drives.
     * <p>
     * Multiple named credentials per provider remain possible via
     * {@link #save(String, String, String, java.util.Map)} — they simply don't
     * participate in this shortcut.
     */
    public ProviderCredentialEntity findPrimary(String tenantId, String providerName) {
        List<ProviderCredentialEntity> rows = listByProvider(tenantId, providerName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Upsert the tenant's primary credential for {@code providerName}. Values not
     * supplied in {@code patch} keep their previous encrypted value — same PATCH
     * semantics as {@link ModelService#updateCredentials} so the UI can rotate just
     * the api key without re-typing base url etc.
     */
    @Transactional
    public ProviderCredentialEntity upsertPrimary(String tenantId, String providerName,
                                                  Map<String, Object> patch) {
        ProviderCredentialEntity existing = findPrimary(tenantId, providerName);
        if (existing == null) {
            return save(tenantId, providerName, providerName + "-primary",
                    patch == null ? new HashMap<>() : patch);
        }
        Map<String, Object> merged = codec.decode(existing.getEncryptedConfig());
        if (patch != null) {
            for (Map.Entry<String, Object> e : patch.entrySet()) {
                if (e.getValue() != null && !(e.getValue() instanceof String s && s.isEmpty())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        existing.setEncryptedConfig(codec.encode(merged));
        mapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void deletePrimary(String tenantId, String providerName) {
        ProviderCredentialEntity existing = findPrimary(tenantId, providerName);
        if (existing != null) {
            mapper.deleteById(existing.getId());
        }
    }

    /**
     * Decode a credential and mask secret fields by name. Any value under a key listed
     * in {@code secretKeys} is replaced with a Dify-style {@code sk-***abcd} preview so
     * the front-end can display the key without ever handling the plaintext.
     */
    public Map<String, Object> maskedView(ProviderCredentialEntity entity, Iterable<String> secretKeys) {
        if (entity == null) {
            return new HashMap<>();
        }
        return codec.obfuscate(codec.decode(entity.getEncryptedConfig()), secretKeys);
    }
}
