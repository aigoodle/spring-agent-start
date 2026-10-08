-- Structured workflow-node failure metadata used by retry policy and operations.
-- Idempotent so it is safe to apply after a partially completed deployment.
ALTER TABLE IF EXISTS goodle_workflow_run_nodes
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(128);

ALTER TABLE IF EXISTS goodle_workflow_run_nodes
    ADD COLUMN IF NOT EXISTS retryable BOOLEAN;
