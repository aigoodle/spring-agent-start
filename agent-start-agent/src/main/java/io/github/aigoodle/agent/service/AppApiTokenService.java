package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AppApiTokenEntity;
import io.github.aigoodle.agent.mapper.AppApiTokenMapper;
import io.github.aigoodle.common.exception.PlatformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * CRUD for per-app API tokens. The token value is generated server-side on
 * create (opaque URL-safe base64) — the frontend never supplies it.
 */
public class AppApiTokenService {

    private static final Logger logger = LoggerFactory.getLogger(AppApiTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String TOKEN_PREFIX = "app-";
    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_TOKEN_NAME = "default";
    private static final String DEFAULT_TOKEN_TYPE = "app";

    private final AppApiTokenMapper tokenMapper;

    public AppApiTokenService(AppApiTokenMapper tokenMapper) {
        this.tokenMapper = tokenMapper;
    }

    public List<AppApiTokenEntity> listByApp(String appId) {
        return tokenMapper.selectList(new LambdaQueryWrapper<AppApiTokenEntity>()
                .eq(AppApiTokenEntity::getAppId, appId)
                .orderByDesc(AppApiTokenEntity::getCreatedAt));
    }

    public AppApiTokenEntity require(String tokenId) {
        AppApiTokenEntity apiToken = tokenMapper.selectById(tokenId);
        if (apiToken == null) {
            throw new PlatformException("api_token_not_found",
                    "API token not found: " + tokenId, null);
        }
        return apiToken;
    }

    /**
     * Resolve a token value to its owning row. Returns {@code null} when the
     * token is missing or unknown — callers decide whether that maps to 401.
     * Backed by {@code idx_api_token_value} so lookup is O(log n).
     */
    public AppApiTokenEntity findByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return tokenMapper.selectOne(new LambdaQueryWrapper<AppApiTokenEntity>()
                .eq(AppApiTokenEntity::getToken, token.trim())
                .last("LIMIT 1"));
    }

    /**
     * Bump {@code last_used_at} to now. Called from the chat request path; the
     * frontend uses this to show a "最后使用" column matching Dify's UX.
     * Deliberately non-transactional and swallows failures — a hiccup here must
     * not fail the enclosing chat call.
     */
    public void touchLastUsed(String tokenId) {
        try {
            AppApiTokenEntity usageUpdate = new AppApiTokenEntity();
            usageUpdate.setId(tokenId);
            usageUpdate.setLastUsedAt(LocalDateTime.now());
            tokenMapper.updateById(usageUpdate);
        } catch (RuntimeException updateFailure) {
            // Best effort: chat continues even when usage metadata cannot be updated.
            logger.debug("Unable to update last-used time for API token {}: {}",
                    tokenId, updateFailure.getMessage());
        }
    }

    @Transactional
    public AppApiTokenEntity create(String appId, String tenantId, String name, String type) {
        AppApiTokenEntity apiToken = new AppApiTokenEntity();
        apiToken.setAppId(appId);
        apiToken.setTenantId(valueOrDefault(tenantId, DEFAULT_TENANT_ID));
        apiToken.setName(valueOrDefault(name, DEFAULT_TOKEN_NAME));
        apiToken.setType(valueOrDefault(type, DEFAULT_TOKEN_TYPE));
        apiToken.setToken(generateToken());
        tokenMapper.insert(apiToken);
        return apiToken;
    }

    @Transactional
    public AppApiTokenEntity rename(String tokenId, String name) {
        AppApiTokenEntity apiToken = require(tokenId);
        if (name != null) {
            apiToken.setName(name);
        }
        tokenMapper.updateById(apiToken);
        return apiToken;
    }

    @Transactional
    public void delete(String tokenId) {
        tokenMapper.deleteById(tokenId);
    }

    private static String generateToken() {
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        return TOKEN_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
