package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppModelConfig;

/** Identifies the app and tenant that own a model configuration sidecar. */
public record AppModelConfigRegistration(
        String appId,
        String tenantId,
        AppModelConfig configuration) {

    public AppModelConfigRegistration {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId must not be blank");
        }
    }
}
