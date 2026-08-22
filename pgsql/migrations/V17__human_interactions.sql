CREATE TABLE IF NOT EXISTS goodle_human_interactions (
    id VARCHAR(64) NOT NULL, tenant_id VARCHAR(64) NOT NULL DEFAULT 'default', workflow_id VARCHAR(64), app_id VARCHAR(64),
    run_id VARCHAR(64) NOT NULL, node_id VARCHAR(255) NOT NULL, conversation_id VARCHAR(64), status VARCHAR(32) NOT NULL,
    title VARCHAR(255), description TEXT, form_mode VARCHAR(32), presentation_mode VARCHAR(32), input_schema_json TEXT,
    access_token_hash VARCHAR(128) NOT NULL, delivery_config_json TEXT, submitted_values_json TEXT, submitted_text TEXT,
    submitted_by VARCHAR(255), short_code VARCHAR(16), channel_provider VARCHAR(64), channel_id VARCHAR(128),
    channel_connection_id VARCHAR(64), channel_target VARCHAR(255), channel_conversation_id VARCHAR(255),
    notification_message_id VARCHAR(255), expires_at TIMESTAMP, submitted_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP,
    PRIMARY KEY (id), UNIQUE (tenant_id, run_id, node_id), UNIQUE (access_token_hash)
);
CREATE INDEX IF NOT EXISTS idx_human_interaction_conversation
    ON goodle_human_interactions (tenant_id, conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_human_interaction_channel ON goodle_human_interactions
    (tenant_id, channel_connection_id, channel_target, status, created_at);
