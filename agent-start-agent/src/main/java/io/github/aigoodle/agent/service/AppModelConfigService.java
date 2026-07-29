package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.mapper.AppModelConfigMapper;
import io.github.aigoodle.common.util.JsonUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code app_model_configs} sidecar row of every app. The row's
 * primary key is the app id itself (1:1 invariant), so all callers go through
 * this service instead of hand-rolling upserts.
 */
public class AppModelConfigService {

    private final AppModelConfigMapper mapper;

    public AppModelConfigService(AppModelConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** Load by app id, or {@code null} when the app has no sidecar yet. */
    public AppModelConfig findByAppId(String appId) {
        if (appId == null || appId.isBlank()) return null;
        return mapper.selectById(appId);
    }

    /**
     * Upsert the sidecar in place. New rows get {@code id == appId} so
     * subsequent selectById calls always hit the same row.
     */
    @Transactional
    public AppModelConfig upsert(String appId, String tenantId, AppModelConfig incoming) {
        if (incoming == null) return null;
        AppModelConfig existing = mapper.selectById(appId);
        incoming.setAppId(appId);
        // Preserve the 1:1 invariant even if the caller left id blank.
        incoming.setId(appId);
        if (tenantId != null && !tenantId.isBlank()) {
            incoming.setTenantId(tenantId);
        }
        if (existing == null) {
            mapper.insert(incoming);
        } else {
            mergeInto(existing, incoming);
            mapper.updateById(existing);
            return existing;
        }
        return incoming;
    }

    /** Delete the sidecar of a specific app (cascade from app delete). */
    @Transactional
    public void deleteByAppId(String appId) {
        if (appId == null || appId.isBlank()) return;
        mapper.deleteById(appId);
    }

    /**
     * Field-level merge that mirrors the "PATCH semantics" the drawer expects:
     * a null incoming field means "no change" — so the console can save one
     * slider without wiping the rest of the row. Non-null incoming values
     * always overwrite (including empty strings — that's the console's way of
     * clearing a field).
     */
    private static void mergeInto(AppModelConfig existing, AppModelConfig incoming) {
        if (incoming.getModelProvider() != null) existing.setModelProvider(incoming.getModelProvider());
        if (incoming.getModelName() != null) existing.setModelName(incoming.getModelName());
        if (incoming.getModelJson() != null) existing.setModelJson(incoming.getModelJson());
        if (incoming.getConfigs() != null) existing.setConfigs(incoming.getConfigs());
        if (incoming.getPrePrompt() != null) existing.setPrePrompt(incoming.getPrePrompt());
        if (incoming.getPromptType() != null) existing.setPromptType(incoming.getPromptType());
        if (incoming.getChatPromptConfig() != null) existing.setChatPromptConfig(incoming.getChatPromptConfig());
        if (incoming.getCompletionPromptConfig() != null)
            existing.setCompletionPromptConfig(incoming.getCompletionPromptConfig());
        if (incoming.getOpeningStatement() != null) existing.setOpeningStatement(incoming.getOpeningStatement());
        if (incoming.getSuggestedQuestionsJson() != null)
            existing.setSuggestedQuestionsJson(incoming.getSuggestedQuestionsJson());
        if (incoming.getSuggestedQuestionsAfterAnswer() != null)
            existing.setSuggestedQuestionsAfterAnswer(incoming.getSuggestedQuestionsAfterAnswer());
        if (incoming.getMoreLikeThis() != null) existing.setMoreLikeThis(incoming.getMoreLikeThis());
        if (incoming.getUserInputFormJson() != null)
            existing.setUserInputFormJson(incoming.getUserInputFormJson());
        if (incoming.getAgentMode() != null) existing.setAgentMode(incoming.getAgentMode());
        if (incoming.getStrategy() != null) existing.setStrategy(incoming.getStrategy());
        if (incoming.getToolNamesJson() != null) existing.setToolNamesJson(incoming.getToolNamesJson());
        if (incoming.getApprovalToolsJson() != null) existing.setApprovalToolsJson(incoming.getApprovalToolsJson());
        if (incoming.getDelegateAgentIdsJson() != null)
            existing.setDelegateAgentIdsJson(incoming.getDelegateAgentIdsJson());
        if (incoming.getMaxIterations() != null) existing.setMaxIterations(incoming.getMaxIterations());
        if (incoming.getMemoryEnabled() != null) existing.setMemoryEnabled(incoming.getMemoryEnabled());
        if (incoming.getMemoryWindow() != null) existing.setMemoryWindow(incoming.getMemoryWindow());
        if (incoming.getDatasetIdsJson() != null) existing.setDatasetIdsJson(incoming.getDatasetIdsJson());
        if (incoming.getDatasetConfigsJson() != null) existing.setDatasetConfigsJson(incoming.getDatasetConfigsJson());
        if (incoming.getFileUploadJson() != null) existing.setFileUploadJson(incoming.getFileUploadJson());
        if (incoming.getExternalDataTools() != null) existing.setExternalDataTools(incoming.getExternalDataTools());
        if (incoming.getRetrieverResource() != null) existing.setRetrieverResource(incoming.getRetrieverResource());
        if (incoming.getDatasetQueryVariable() != null)
            existing.setDatasetQueryVariable(incoming.getDatasetQueryVariable());
        if (incoming.getSpeechToText() != null) existing.setSpeechToText(incoming.getSpeechToText());
        if (incoming.getTextToSpeech() != null) existing.setTextToSpeech(incoming.getTextToSpeech());
        if (incoming.getSensitiveWordAvoidance() != null)
            existing.setSensitiveWordAvoidance(incoming.getSensitiveWordAvoidance());
    }

    /**
     * Translate a {@link CreateAgentRequest} payload — the flat "app + drawer"
     * shape the front-end uses today — into an {@link AppModelConfig} the
     * sidecar can persist. Any field the request left null is left null on the
     * output so the {@code mergeInto} PATCH semantic above kicks in.
     */
    public static AppModelConfig fromRequest(CreateAgentRequest req) {
        if (req == null) return null;
        AppModelConfig cfg = new AppModelConfig();
        cfg.setModelProvider(req.getModelProvider());
        cfg.setModelName(req.getModelName());
        cfg.setConfigs(JsonUtils.toJson(req.getModelSettings()));
        cfg.setPrePrompt(pickPrompt(req));
        cfg.setPromptType(req.getPromptType());
        cfg.setOpeningStatement(req.getOpeningStatement());
        cfg.setSuggestedQuestionsJson(JsonUtils.toJson(req.getSuggestedQuestions()));
        cfg.setUserInputFormJson(JsonUtils.toJson(req.getUserInputForm()));
        cfg.setStrategy(req.getStrategy() == null ? null : req.getStrategy().name());
        cfg.setToolNamesJson(JsonUtils.toJson(req.getToolNames()));
        cfg.setApprovalToolsJson(JsonUtils.toJson(req.getApprovalRequiredTools()));
        cfg.setDelegateAgentIdsJson(JsonUtils.toJson(req.getDelegateAgentIds()));
        cfg.setMaxIterations(req.getMaxIterations() == 0 ? null : req.getMaxIterations());
        cfg.setMemoryEnabled(req.isMemoryEnabled());
        cfg.setMemoryWindow(req.getMemoryWindow() == 0 ? null : req.getMemoryWindow());
        cfg.setDatasetIdsJson(JsonUtils.toJson(req.getDatasetIds()));
        cfg.setDatasetConfigsJson(JsonUtils.toJson(req.getRetrievalConfig()));
        cfg.setFileUploadJson(JsonUtils.toJson(req.getFileUpload()));
        return cfg;
    }

    /**
     * Legacy compatibility: earlier drawers sent the system prompt as
     * {@code instructions}; newer ones send {@code prePrompt}. Prefer the
     * explicit prePrompt when present.
     */
    private static String pickPrompt(CreateAgentRequest req) {
        if (req.getPrePrompt() != null && !req.getPrePrompt().isBlank()) return req.getPrePrompt();
        return req.getInstructions();
    }
}
