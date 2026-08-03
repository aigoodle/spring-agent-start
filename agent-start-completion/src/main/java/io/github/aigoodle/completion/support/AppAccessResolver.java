package io.github.aigoodle.completion.support;

import io.github.aigoodle.agent.entity.ApiTokenEntity;
import io.github.aigoodle.agent.service.ApiTokenService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.dto.dify.DifyChatMessagesRequest;
import org.springframework.beans.factory.ObjectProvider;

/** Resolves hosted applications from explicit ids or persisted API tokens. */
public final class AppAccessResolver {

    private final ObjectProvider<ApiTokenService> apiTokenServiceProvider;

    public AppAccessResolver(ObjectProvider<ApiTokenService> apiTokenServiceProvider) {
        this.apiTokenServiceProvider = apiTokenServiceProvider;
    }

    public String enforcePathApp(String pathAppId, String authorizationHeader,
                                 boolean debugRun) {
        if (debugRun) {
            return pathAppId;
        }
        ApiTokenEntity token = findToken(authorizationHeader);
        if (token == null) {
            return pathAppId;
        }
        if (pathAppId != null && !pathAppId.isBlank()
                && !pathAppId.equals(token.getAppId())) {
            throw new AgentException("api_key_app_mismatch",
                    "该 API Key 不属于目标应用 " + pathAppId, null);
        }
        touch(token);
        return token.getAppId();
    }

    public String resolveDifyApp(String queryAppId, String headerAppId,
                                 String authorizationHeader,
                                 DifyChatMessagesRequest request) {
        ApiTokenEntity token = findToken(authorizationHeader);
        if (token != null) {
            touch(token);
            return token.getAppId();
        }

        String appId = firstNonBlank(queryAppId, headerAppId,
                extractBearerToken(authorizationHeader), request == null ? null : request.getAppId());
        if (appId != null) {
            return appId;
        }
        if (request != null && request.getInputs() != null) {
            Object inputAppId = request.getInputs().get("app_id");
            if (inputAppId == null) {
                inputAppId = request.getInputs().get("appId");
            }
            appId = inputAppId == null ? null : trimToNull(inputAppId.toString());
            if (appId != null) {
                return appId;
            }
        }
        throw new AgentException("missing_app_id",
                "无法识别目标应用，请通过 appId 参数、X-App-Id、Authorization、"
                        + "body.appId 或 inputs.app_id 提供", null);
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ApiTokenEntity findToken(String authorizationHeader) {
        String bearerToken = extractBearerToken(authorizationHeader);
        ApiTokenService tokenService = apiTokenServiceProvider.getIfAvailable();
        if (bearerToken == null || tokenService == null) {
            return null;
        }
        return tokenService.findByToken(bearerToken);
    }

    private void touch(ApiTokenEntity token) {
        ApiTokenService tokenService = apiTokenServiceProvider.getIfAvailable();
        if (tokenService != null) {
            tokenService.touchLastUsed(token.getId());
        }
    }

    private static String extractBearerToken(String authorizationHeader) {
        String value = trimToNull(authorizationHeader);
        if (value == null) {
            return null;
        }
        if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return trimToNull(value.substring("Bearer ".length()));
        }
        return value;
    }
}
