package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.common.util.JsonUtils;

/** Translates an agent-editor request into its model-configuration sidecar. */
final class AppModelConfigFactory {

    private AppModelConfigFactory() {
    }

    static AppModelConfig from(CreateAgentRequest request) {
        if (request == null) {
            return null;
        }

        AppModelConfig sidecar = new AppModelConfig();
        applyModelConfiguration(request, sidecar);
        applyPromptConfiguration(request, sidecar);
        applyAgentConfiguration(request, sidecar);
        applyKnowledgeConfiguration(request, sidecar);
        return sidecar;
    }

    private static void applyModelConfiguration(CreateAgentRequest request,
                                                AppModelConfig sidecar) {
        sidecar.setModelProvider(request.getModelProvider());
        sidecar.setModelName(request.getModelName());
        sidecar.setConfigs(toJson(request.getModelSettings()));
    }

    private static void applyPromptConfiguration(CreateAgentRequest request,
                                                 AppModelConfig sidecar) {
        sidecar.setPrePrompt(preferredSystemPrompt(request));
        sidecar.setPromptType(request.getPromptType());
        sidecar.setOpeningStatement(request.getOpeningStatement());
        sidecar.setSuggestedQuestionsJson(toJson(request.getSuggestedQuestions()));
        sidecar.setUserInputFormJson(toJson(request.getUserInputForm()));
        sidecar.setFileUploadJson(toJson(request.getFileUpload()));
    }

    private static void applyAgentConfiguration(CreateAgentRequest request,
                                                AppModelConfig sidecar) {
        sidecar.setStrategy(strategyName(request.getStrategy()));
        sidecar.setToolNamesJson(toJson(request.getToolNames()));
        sidecar.setApprovalToolsJson(toJson(request.getApprovalRequiredTools()));
        sidecar.setDelegateAgentIdsJson(toJson(request.getDelegateAgentIds()));
        sidecar.setMaxIterations(positiveOrNull(request.getMaxIterations()));
        sidecar.setMemoryEnabled(request.isMemoryEnabled());
        sidecar.setMemoryWindow(positiveOrNull(request.getMemoryWindow()));
    }

    private static void applyKnowledgeConfiguration(CreateAgentRequest request,
                                                    AppModelConfig sidecar) {
        sidecar.setDatasetIdsJson(toJson(request.getDatasetIds()));
        sidecar.setDatasetConfigsJson(toJson(request.getRetrievalConfig()));
    }

    private static String preferredSystemPrompt(CreateAgentRequest request) {
        return hasText(request.getPrePrompt())
                ? request.getPrePrompt()
                : request.getInstructions();
    }

    private static String strategyName(AgentStrategyType strategy) {
        return strategy == null ? null : strategy.name();
    }

    private static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    private static String toJson(Object value) {
        return JsonUtils.toJson(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
