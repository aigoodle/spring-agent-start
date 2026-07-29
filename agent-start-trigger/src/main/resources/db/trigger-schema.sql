-- agent-start-trigger schema (portable across H2 and MySQL/Postgres).
-- Table names aligned with spring-agent-start:
--   app_triggers          ← was agent_trigger
--   trigger_invocations   ← was agent_trigger_invocation

CREATE TABLE IF NOT EXISTS app_triggers (
    id          VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    name        VARCHAR(255),
    type        VARCHAR(32),
    enabled     BOOLEAN DEFAULT TRUE,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    config_json TEXT,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS trigger_invocations (
    id           VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    trigger_id   VARCHAR(64) NOT NULL,
    source       VARCHAR(32),
    status       VARCHAR(32),
    payload_json TEXT,
    run_id       VARCHAR(64),
    outputs_json TEXT,
    error        TEXT,
    replay_of    VARCHAR(64),
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_trigger_type ON app_triggers (type, enabled);
CREATE INDEX IF NOT EXISTS idx_invocation_trigger ON trigger_invocations (trigger_id);
