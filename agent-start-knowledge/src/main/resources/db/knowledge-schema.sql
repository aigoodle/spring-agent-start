-- agent-start-knowledge schema (portable across H2 and MySQL).
-- Table names aligned with the reference spring-agent-start project (Dify-parity):
--   dataset               ← was agent_dataset
--   documents             ← was agent_knowledge_document
--   document_segments     ← was agent_segment
--   embeddings            ← was agent_vector (JDBC vector-store fallback)
--   dataset_query         ← was agent_dataset_hit_test_log

CREATE TABLE IF NOT EXISTS dataset (
    id                    VARCHAR(64)  NOT NULL,
    tenant_id             VARCHAR(64)  NOT NULL DEFAULT 'default',
    name                  VARCHAR(255) NOT NULL,
    description           TEXT,
    embedding_model_id    VARCHAR(64),
    indexing_technique    VARCHAR(32),
    process_rule_json     TEXT,
    retrieval_config_json TEXT,
    vector_store          VARCHAR(64),
    document_count        INT DEFAULT 0,
    segment_count         INT DEFAULT 0,
    created_at            TIMESTAMP,
    updated_at            TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS documents (
    id            VARCHAR(64)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
    dataset_id    VARCHAR(64)  NOT NULL,
    name          VARCHAR(512),
    source_type   VARCHAR(32),
    status        VARCHAR(32),
    error_message TEXT,
    word_count    INT,
    segment_count INT,
    enabled       BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    PRIMARY KEY (id)
);

-- Sidecar table for the async ingestion queue. Presence of a row means the
-- corresponding document is still "in flight" (PARSING / PENDING / CHUNKING /
-- INDEXING); the async runner deletes the row on COMPLETED. Keeping raw_text
-- here instead of on `documents` keeps list queries lightweight — no
-- multi-MB text blobs coming back on a `SELECT * FROM documents`.
CREATE TABLE IF NOT EXISTS document_ingest_queue (
    document_id VARCHAR(64) NOT NULL,
    dataset_id  VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    filename    VARCHAR(512),
    source_type VARCHAR(32),
    raw_text    TEXT,
    retry_count INT DEFAULT 0,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    PRIMARY KEY (document_id)
);
CREATE INDEX IF NOT EXISTS idx_ingest_queue_dataset ON document_ingest_queue (dataset_id);

CREATE TABLE IF NOT EXISTS document_segments (
    id            VARCHAR(64)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
    dataset_id    VARCHAR(64)  NOT NULL,
    document_id   VARCHAR(64)  NOT NULL,
    position      INT,
    content       TEXT,
    token_count   INT,
    keywords      TEXT,
    metadata_json TEXT,
    parent_id     VARCHAR(64),
    vector_id     VARCHAR(64),
    enabled       BOOLEAN DEFAULT TRUE,
    hash          VARCHAR(32),
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    PRIMARY KEY (id)
);

-- Optional table for the built-in JDBC vector store (spring-agent.knowledge.vector-store=jdbc).
CREATE TABLE IF NOT EXISTS embeddings (
    id            VARCHAR(64) NOT NULL,
    dataset_id    VARCHAR(64) NOT NULL,
    content       TEXT,
    metadata_json TEXT,
    embedding     TEXT,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_embeddings_dataset ON embeddings (dataset_id);

CREATE INDEX IF NOT EXISTS idx_doc_dataset ON documents (dataset_id);
CREATE INDEX IF NOT EXISTS idx_segment_dataset ON document_segments (dataset_id);
CREATE INDEX IF NOT EXISTS idx_segment_document ON document_segments (document_id);

-- Retrieval query log: every dry-run + production retrieval recorded for later
-- comparison / debugging. Powers the "recent queries" panel of the dataset
-- detail page so users can eyeball retrieval quality drift.
CREATE TABLE IF NOT EXISTS dataset_query (
    id           VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    dataset_id   VARCHAR(64) NOT NULL,
    query        TEXT,
    method       VARCHAR(32),
    top_k        INT,
    results_json TEXT,
    hit_count    INT,
    latency_ms   INT,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dataset_query_dataset ON dataset_query (dataset_id);
