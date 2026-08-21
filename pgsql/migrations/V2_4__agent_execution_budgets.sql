ALTER TABLE goodle_app_model_configs
    ADD COLUMN IF NOT EXISTS max_model_calls INTEGER;

ALTER TABLE goodle_app_model_configs
    ADD COLUMN IF NOT EXISTS max_tool_calls INTEGER;
