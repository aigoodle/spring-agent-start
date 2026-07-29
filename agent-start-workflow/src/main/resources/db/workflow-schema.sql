-- agent-start-workflow schema (portable across H2 and MySQL).
-- Table names aligned with spring-agent-start:
--   workflows       ← was agent_workflow
--   workflow_runs   ← was agent_workflow_run

-- workflows carries app-scoped drafts + published snapshots (Dify parity).
--
-- Invariant: for a workflow-mode app, exactly one row has {id = app.id,
-- version = 'draft'} — the mutable working copy. Publishing copies its
-- graph_json into a *new* row with a fresh id + timestamp-shaped version and
-- points apps.workflow_id at that snapshot; the draft row keeps its id so
-- subsequent edits always know where to write.
--
-- Standalone (app_id = null) workflows still work for the /workflows debug
-- endpoints so the JSON playground doesn't need an app.
CREATE TABLE IF NOT EXISTS workflows (
    id                      VARCHAR(64)  NOT NULL,
    tenant_id               VARCHAR(64)  NOT NULL DEFAULT 'default',
    app_id                  VARCHAR(64),
    name                    VARCHAR(255),
    description             TEXT,
    mode                    VARCHAR(32),
    graph                   TEXT,
    -- 'draft' or a snapshot label like '1.0' / a timestamp.
    version                 VARCHAR(32) DEFAULT 'draft',
    published               BOOLEAN DEFAULT FALSE,
    features                TEXT,
    environment_variables   TEXT,
    conversation_variables  TEXT,
    output                  TEXT,
    marked_name             VARCHAR(255),
    marked_comment          VARCHAR(1024),
    created_at              TIMESTAMP,
    updated_at              TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_workflow_app ON workflows (app_id, version);
CREATE INDEX IF NOT EXISTS idx_workflow_tenant ON workflows (tenant_id);

CREATE TABLE IF NOT EXISTS workflow_runs (
    id              VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    workflow_id     VARCHAR(64),
    conversation_id VARCHAR(64),
    status          VARCHAR(32),
    inputs_json     TEXT,
    outputs_json    TEXT,
    steps_json      TEXT,
    error           TEXT,
    elapsed_millis  BIGINT,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_run_workflow ON workflow_runs (workflow_id);
