-- Persist the authenticated creator so background scheduler threads can restore
-- the same tenant/user context before dispatching the target workflow.
ALTER TABLE goodle_app_triggers
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_trigger_tenant_user
    ON goodle_app_triggers (tenant_id, user_id);
