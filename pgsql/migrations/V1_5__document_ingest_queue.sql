-- ============================================================================
-- Migration V1.5 · document_ingest_queue table
-- ----------------------------------------------------------------------------
-- Adds the sidecar table backing the async knowledge-ingest pipeline
-- (opt-in via spring-agent.knowledge.async.enabled=true). Presence of a row
-- means the corresponding document row (status PENDING/PARSING/CHUNKING/
-- INDEXING) is still being processed by the worker; the row is deleted once
-- ingestion completes.
--
-- Kept separate from `documents` so multi-MB raw_text blobs don't come back
-- on the card-grid list query.
--
-- Idempotent — safe to run twice.
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS document_ingest_queue (
    document_id VARCHAR(64) NOT NULL,
    dataset_id  VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    filename    VARCHAR(512),
    source_type VARCHAR(32),
    raw_text    TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    PRIMARY KEY (document_id)
);
CREATE INDEX IF NOT EXISTS idx_ingest_queue_dataset ON document_ingest_queue (dataset_id);

COMMIT;
