package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AgentEntity;

import java.util.function.Consumer;

/** Applies catalog-facing request fields to an application's persisted catalog row. */
final class AgentCatalogUpdater {

    private static final String DEFAULT_MODE = "agent";
    private static final String DEFAULT_STATUS = "normal";

    void applyRequest(CreateAgentRequest request, AgentEntity agent) {
        applyWhenPresent(request.getName(), agent::setName);
        applyWhenPresent(request.getDescription(), agent::setDescription);
        applyWhenPresent(request.getIcon(), agent::setIcon);
        applyWhenPresent(request.getIconBackground(), agent::setIconBackground);
        applyWhenPresent(request.getIconType(), agent::setIconType);
        applyWhenPresent(request.getUseIconAsAnswerIcon(), agent::setUseIconAsAnswerIcon);
        applyWhenPresent(request.getIsPublic(), agent::setIsPublic);
        applyWhenPresent(request.getEnableSite(), agent::setEnableSite);
        applyWhenPresent(request.getEnableApi(), agent::setEnableApi);
        applyWhenPresent(request.getApiRpm(), agent::setApiRpm);
        applyWhenPresent(request.getApiRph(), agent::setApiRph);
        applyWhenPresent(request.getModelName(), agent::setModelName);
        applyWhenPresent(request.getModelProvider(), agent::setModelProvider);

        agent.setMode(requestedOrCurrentOrDefault(
                request.getMode(), agent.getMode(), DEFAULT_MODE));
        agent.setStatus(requestedOrCurrentOrDefault(
                request.getStatus(), agent.getStatus(), DEFAULT_STATUS));
        agent.setPublished(request.isPublished());
    }

    private static String requestedOrCurrentOrDefault(String requestedValue,
                                                       String currentValue,
                                                       String defaultValue) {
        if (hasText(requestedValue)) {
            return requestedValue;
        }
        return hasText(currentValue) ? currentValue : defaultValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> void applyWhenPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
