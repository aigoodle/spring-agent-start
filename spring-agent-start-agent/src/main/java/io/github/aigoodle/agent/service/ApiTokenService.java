package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.ApiTokenEntity;
import io.github.aigoodle.agent.mapper.ApiTokenMapper;
import io.github.aigoodle.common.exception.AgentException;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * CRUD for per-app API tokens. The token value is generated server-side on
 * create (opaque URL-safe base64) — the frontend never supplies it.
 */
public class ApiTokenService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TOKEN_PREFIX = "app-";

    private final ApiTokenMapper mapper;

    public ApiTokenService(ApiTokenMapper mapper) {
        this.mapper = mapper;
    }

    public List<ApiTokenEntity> listByApp(String appId) {
        return mapper.selectList(new LambdaQueryWrapper<ApiTokenEntity>()
                .eq(ApiTokenEntity::getAppId, appId)
                .orderByDesc(ApiTokenEntity::getCreatedAt));
    }

    public ApiTokenEntity require(String id) {
        ApiTokenEntity e = mapper.selectById(id);
        if (e == null) {
            throw new AgentException("api_token_not_found", "API token not found: " + id, null);
        }
        return e;
    }

    /**
     * Resolve a token value to its owning row. Returns {@code null} when the
     * token is missing or unknown — callers decide whether that maps to 401.
     * Backed by {@code idx_api_token_value} so lookup is O(log n).
     */
    public ApiTokenEntity findByToken(String token) {
        if (token == null || token.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<ApiTokenEntity>()
                .eq(ApiTokenEntity::getToken, token.trim())
                .last("LIMIT 1"));
    }

    /**
     * Bump {@code last_used_at} to now. Called from the chat request path; the
     * frontend uses this to show a "最后使用" column matching Dify's UX.
     * Deliberately non-transactional and swallows failures — a hiccup here must
     * not fail the enclosing chat call.
     */
    public void touchLastUsed(String id) {
        try {
            ApiTokenEntity patch = new ApiTokenEntity();
            patch.setId(id);
            patch.setLastUsedAt(LocalDateTime.now());
            mapper.updateById(patch);
        } catch (Exception ignore) {
            // best-effort — chat flow continues even if the bump fails
        }
    }

    @Transactional
    public ApiTokenEntity create(String appId, String tenantId, String name, String type) {
        ApiTokenEntity e = new ApiTokenEntity();
        e.setAppId(appId);
        e.setTenantId(tenantId == null ? "default" : tenantId);
        e.setName(name == null || name.isBlank() ? "default" : name);
        e.setType(type == null || type.isBlank() ? "app" : type);
        e.setToken(generate());
        mapper.insert(e);
        return e;
    }

    @Transactional
    public ApiTokenEntity rename(String id, String name) {
        ApiTokenEntity e = require(id);
        if (name != null) e.setName(name);
        mapper.updateById(e);
        return e;
    }

    @Transactional
    public void delete(String id) {
        mapper.deleteById(id);
    }

    private static String generate() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
