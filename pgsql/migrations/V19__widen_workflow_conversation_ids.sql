-- Channel-backed conversation keys include tenant, connection and provider
-- conversation identifiers and can legitimately exceed 64 characters.
ALTER TABLE IF EXISTS goodle_workflow_runs
    ALTER COLUMN conversation_id TYPE VARCHAR(255);

ALTER TABLE IF EXISTS goodle_workflow_checkpoints
    ALTER COLUMN conversation_id TYPE VARCHAR(255);

ALTER TABLE IF EXISTS goodle_human_interactions
    ALTER COLUMN conversation_id TYPE VARCHAR(255);
