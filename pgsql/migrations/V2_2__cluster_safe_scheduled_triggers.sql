-- Persistent scheduler cursor and lease. The conditional UPDATE in TriggerMapper
-- guarantees only one application node owns a due occurrence.
ALTER TABLE goodle_app_triggers
    ADD COLUMN IF NOT EXISTS next_fire_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_fire_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS lock_until TIMESTAMP,
    ADD COLUMN IF NOT EXISTS lock_owner VARCHAR(128),
    ADD COLUMN IF NOT EXISTS fire_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE goodle_trigger_invocations
    ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_trigger_due
    ON goodle_app_triggers (enabled, type, next_fire_at);

-- Existing cron definitions become eligible without recreating them. The service
-- recalculates exact next_fire_at whenever they are saved/toggled.
UPDATE goodle_app_triggers
SET next_fire_at = CURRENT_TIMESTAMP
WHERE type = 'CRON' AND enabled = TRUE AND next_fire_at IS NULL;
