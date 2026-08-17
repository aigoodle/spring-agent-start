package io.github.aigoodle.completion.support;

/**
 * Host integration point for chat authorization. The starter deliberately
 * does not prescribe JWT, sessions, Spring Security, or another login system.
 */
public interface ChatAccessPolicy {

    ChatAccessContext authorizeInternal(String appId);

    default ChatAccessContext authorizeInternalByCode(String appCode) {
        throw new io.github.aigoodle.common.exception.AgentException(
                "app_code_unsupported", "当前宿主未启用应用编码访问", null);
    }

    ChatAccessContext authorizeDebug(String appId, String workflowId);

    ChatAccessContext authorizeExternal(String appId);
}
