-- ============================================================================
-- Migration V1.7 · apps.model_settings_json column
-- ----------------------------------------------------------------------------
-- Per-app model runtime overrides. The model-picker drawer on the app config
-- page now saves temperature, topP, maxTokens, presencePenalty,
-- frequencyPenalty and a normalized thinkingMode (auto|enabled|disabled)
-- against the app instead of the underlying agent_model row, so switching
-- models on the same app keeps the app-level slider positions intact.
--
-- The blob is opaque JSON. Vendor-specific extras (Qwen enable_thinking,
-- Ollama think, Doubao thinking.type) are derived from thinkingMode at
-- runtime via AgentChatOptionsFactory using apps.model_provider, so the row
-- stays vendor-neutral.
-- ============================================================================

ALTER TABLE apps
    ADD COLUMN IF NOT EXISTS model_settings_json TEXT;
