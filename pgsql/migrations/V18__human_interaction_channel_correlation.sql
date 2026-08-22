ALTER TABLE goodle_human_interactions ADD COLUMN IF NOT EXISTS short_code VARCHAR(16);
ALTER TABLE goodle_human_interactions ADD COLUMN IF NOT EXISTS channel_provider VARCHAR(64);
ALTER TABLE goodle_human_interactions ADD COLUMN IF NOT EXISTS channel_id VARCHAR(128);
ALTER TABLE goodle_human_interactions ADD COLUMN IF NOT EXISTS channel_connection_id VARCHAR(64);
ALTER TABLE goodle_human_interactions ADD COLUMN IF NOT EXISTS channel_target VARCHAR(255);
ALTER TABLE goodle_human_interactions ADD COLUMN IF NOT EXISTS channel_conversation_id VARCHAR(255);
ALTER TABLE goodle_human_interactions ADD COLUMN IF NOT EXISTS notification_message_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_human_interaction_channel ON goodle_human_interactions
    (tenant_id, channel_connection_id, channel_target, status, created_at);
