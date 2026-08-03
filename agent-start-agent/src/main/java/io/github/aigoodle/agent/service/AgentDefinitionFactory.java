package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Combines an application's catalog row and model sidecar into runtime views. */
final class AgentDefinitionFactory {

    private static final int DEFAULT_MAX_ITERATIONS = 6;
    private static final int DEFAULT_MEMORY_WINDOW = 20;

    private final AppModelConfigService modelConfigService;

    AgentDefinitionFactory(AppModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    AgentEntity enrich(AgentEntity agent) {
        if (agent == null || agent.getId() == null) {
            return agent;
        }
        AppModelConfig modelConfig = modelConfigService.findByAppId(agent.getId());
        if (modelConfig == null) {
            return agent;
        }

        agent.setInstructions(modelConfig.getPrePrompt());
        agent.setOpeningStatement(modelConfig.getOpeningStatement());
        agent.setSuggestedQuestionsJson(modelConfig.getSuggestedQuestionsJson());
        agent.setDatasetIdsJson(modelConfig.getDatasetIdsJson());
        agent.setRetrievalConfigJson(modelConfig.getDatasetConfigsJson());
        agent.setModelSettingsJson(modelConfig.getConfigs());
        agent.setStrategy(modelConfig.getStrategy());
        agent.setToolNamesJson(modelConfig.getToolNamesJson());
        agent.setApprovalToolsJson(modelConfig.getApprovalToolsJson());
        agent.setDelegateAgentIdsJson(modelConfig.getDelegateAgentIdsJson());
        agent.setMaxIterations(modelConfig.getMaxIterations());
        agent.setMemoryEnabled(modelConfig.getMemoryEnabled());
        agent.setMemoryWindow(modelConfig.getMemoryWindow());
        agent.setModelName(firstText(modelConfig.getModelName(), agent.getModelName()));
        agent.setModelProvider(firstText(modelConfig.getModelProvider(), agent.getModelProvider()));
        return agent;
    }

    AgentDefinition create(AgentEntity agent) {
        AppModelConfig modelConfig = modelConfigService.findByAppId(agent.getId());
        return AgentDefinition.builder()
                .id(agent.getId())
                .tenantId(agent.getTenantId())
                .name(agent.getName())
                .instructions(configuredValue(modelConfig, AppModelConfig::getPrePrompt))
                .modelName(firstText(
                        configuredValue(modelConfig, AppModelConfig::getModelName),
                        agent.getModelName()))
                .modelProvider(firstText(
                        configuredValue(modelConfig, AppModelConfig::getModelProvider),
                        agent.getModelProvider()))
                .strategy(resolveStrategy(modelConfig))
                .toolNames(parseStringList(
                        configuredValue(modelConfig, AppModelConfig::getToolNamesJson)))
                .approvalRequiredTools(new HashSet<>(parseStringList(
                        configuredValue(modelConfig, AppModelConfig::getApprovalToolsJson))))
                .delegateAgentIds(parseStringList(
                        configuredValue(modelConfig, AppModelConfig::getDelegateAgentIdsJson)))
                .maxIterations(valueOrDefault(
                        configuredValue(modelConfig, AppModelConfig::getMaxIterations),
                        DEFAULT_MAX_ITERATIONS))
                .memoryEnabled(!Boolean.FALSE.equals(
                        configuredValue(modelConfig, AppModelConfig::getMemoryEnabled)))
                .memoryWindow(valueOrDefault(
                        configuredValue(modelConfig, AppModelConfig::getMemoryWindow),
                        DEFAULT_MEMORY_WINDOW))
                .modelSettings(parseSettings(
                        configuredValue(modelConfig, AppModelConfig::getConfigs)))
                .build();
    }

    private static AgentStrategyType resolveStrategy(AppModelConfig modelConfig) {
        String configuredStrategy = configuredValue(modelConfig, AppModelConfig::getStrategy);
        if (!hasText(configuredStrategy)) {
            return AgentStrategyType.REACT;
        }

        String enumName = configuredStrategy.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return AgentStrategyType.valueOf(enumName);
        } catch (IllegalArgumentException exception) {
            throw new AgentException(
                    "invalid_agent_strategy",
                    "Unsupported agent strategy: " + configuredStrategy,
                    exception);
        }
    }

    private static List<String> parseStringList(String json) {
        return hasText(json) ? JsonUtils.parseList(json, String.class) : List.of();
    }

    private static Map<String, Object> parseSettings(String json) {
        if (!hasText(json)) {
            return new HashMap<>();
        }
        Map<String, Object> settings = JsonUtils.parseMap(json);
        return settings == null ? new HashMap<>() : new HashMap<>(settings);
    }

    private static String firstText(String preferredValue, String fallbackValue) {
        return hasText(preferredValue) ? preferredValue : fallbackValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static <T> T configuredValue(AppModelConfig modelConfig,
                                         Function<AppModelConfig, T> valueExtractor) {
        return modelConfig == null ? null : valueExtractor.apply(modelConfig);
    }
}
