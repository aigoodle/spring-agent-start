package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppModelConfig;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Applies non-null model-configuration fields while preserving omitted settings. */
final class AppModelConfigPatch {

    private AppModelConfigPatch() {
    }

    static void apply(AppModelConfig target, AppModelConfig patch) {
        copyIfPresent(patch::getModelProvider, target::setModelProvider);
        copyIfPresent(patch::getModelName, target::setModelName);
        copyIfPresent(patch::getModelJson, target::setModelJson);
        copyIfPresent(patch::getConfigs, target::setConfigs);
        copyIfPresent(patch::getPrePrompt, target::setPrePrompt);
        copyIfPresent(patch::getPromptType, target::setPromptType);
        copyIfPresent(patch::getChatPromptConfig, target::setChatPromptConfig);
        copyIfPresent(patch::getCompletionPromptConfig, target::setCompletionPromptConfig);
        copyIfPresent(patch::getOpeningStatement, target::setOpeningStatement);
        copyIfPresent(patch::getSuggestedQuestionsJson, target::setSuggestedQuestionsJson);
        copyIfPresent(
                patch::getSuggestedQuestionsAfterAnswer,
                target::setSuggestedQuestionsAfterAnswer);
        copyIfPresent(patch::getMoreLikeThis, target::setMoreLikeThis);
        copyIfPresent(patch::getUserInputFormJson, target::setUserInputFormJson);
        copyIfPresent(patch::getAgentMode, target::setAgentMode);
        copyIfPresent(patch::getStrategy, target::setStrategy);
        copyIfPresent(patch::getToolNamesJson, target::setToolNamesJson);
        copyIfPresent(patch::getApprovalToolsJson, target::setApprovalToolsJson);
        copyIfPresent(patch::getDelegateAgentIdsJson, target::setDelegateAgentIdsJson);
        copyIfPresent(patch::getMaxIterations, target::setMaxIterations);
        copyIfPresent(patch::getMemoryEnabled, target::setMemoryEnabled);
        copyIfPresent(patch::getMemoryWindow, target::setMemoryWindow);
        copyIfPresent(patch::getDatasetIdsJson, target::setDatasetIdsJson);
        copyIfPresent(patch::getDatasetConfigsJson, target::setDatasetConfigsJson);
        copyIfPresent(patch::getFileUploadJson, target::setFileUploadJson);
        copyIfPresent(patch::getExternalDataTools, target::setExternalDataTools);
        copyIfPresent(patch::getRetrieverResource, target::setRetrieverResource);
        copyIfPresent(patch::getDatasetQueryVariable, target::setDatasetQueryVariable);
        copyIfPresent(patch::getSpeechToText, target::setSpeechToText);
        copyIfPresent(patch::getTextToSpeech, target::setTextToSpeech);
        copyIfPresent(patch::getSensitiveWordAvoidance, target::setSensitiveWordAvoidance);
    }

    private static <T> void copyIfPresent(Supplier<T> source, Consumer<T> destination) {
        T value = source.get();
        if (value != null) {
            destination.accept(value);
        }
    }
}
