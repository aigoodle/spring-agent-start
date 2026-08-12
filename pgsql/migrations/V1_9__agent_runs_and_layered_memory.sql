-- Durable Agent Run state/checkpoints and the standalone layered-memory module.
-- Idempotent so it can upgrade installations created before these modules existed.

CREATE TABLE IF NOT EXISTS agent_runs (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    agent_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    definition_json TEXT,
    request_json TEXT,
    response_json TEXT,
    error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    event_sequence BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS event_sequence BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_agent_run_agent_created ON agent_runs (agent_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_run_conversation ON agent_runs (conversation_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON agent_runs (status);

CREATE TABLE IF NOT EXISTS agent_run_events (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    run_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_agent_run_event_stream
    ON agent_run_events (run_id, sequence_no);

-- Existing lifecycle events may predate event_sequence. Continue after the largest
-- persisted sequence so the CAS allocator never reuses an event number.
UPDATE agent_runs r
SET event_sequence = greatest(
        r.event_sequence,
        COALESCE((SELECT max(e.sequence_no) FROM agent_run_events e WHERE e.run_id = r.id), 0)
    );

CREATE TABLE IF NOT EXISTS agent_memories (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    owner_id VARCHAR(64),
    conversation_id VARCHAR(128),
    tier VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    expires_at TIMESTAMP,
    access_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_memories_scope
    ON agent_memories (tenant_id, owner_id, conversation_id, tier, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_memories_expiry ON agent_memories (expires_at);
