-- =============================================================================
-- spring-agent-start · PostgreSQL init script
-- -----------------------------------------------------------------------------
-- Consolidated schema for every functional module, PG-native types.
-- Idempotent: safe to re-run (uses IF NOT EXISTS everywhere).
--
-- Usage (bare psql):
--     createdb spring_agent_boot
--     psql -d spring_agent_boot -f pgsql/init.sql
--
-- Usage (docker-compose init):
--     Mount this file as /docker-entrypoint-initdb.d/init.sql on the postgres
--     service — docker/postgres/docker-compose.yml does this.
--
-- Usage (agent-start-server postgres profile):
--     The Spring Boot app itself also runs each module's classpath schema.sql,
--     but that path is portable H2/MySQL. Prefer this file when standing up a
--     fresh PostgreSQL for production, then start the app with:
--         --spring.profiles.active=postgres
-- =============================================================================

-- ============================================================================
--  MODEL MODULE (Dify-parity 6-table split)
--    · agent_model_provider         — provider definitions (DB-driven, seeded from Java built-ins)
--    · agent_predefined_model       — provider catalog (DB-driven, replaces Java predefinedModels())
--    · agent_provider_credential    — encrypted per-tenant credentials
--    · agent_model                  — per-tenant CUSTOM model registrations (with overrides)
--    · agent_provider_model_setting — per-tenant model enable/disable (missing row = enabled)
--    · agent_tenant_default_model   — per-tenant default per model type
--    · agent_prompt_template        — reusable prompt templates
-- ============================================================================

-- Provider definitions. `source='builtin'` rows are seeded from Java at first boot.
-- `source='external'|'custom'` can be added via /api/v1/model-provider-definitions
-- without touching Java code — supporting the "extend supported providers via DB
-- rather than code" flow.
CREATE TABLE IF NOT EXISTS agent_model_provider (
    id                             VARCHAR(64)  NOT NULL,
    tenant_id                      VARCHAR(64)  NOT NULL DEFAULT 'system',
    name                           VARCHAR(255) NOT NULL,
    label                          VARCHAR(255) NOT NULL,
    description                    VARCHAR(1024),
    icon                           VARCHAR(255),
    svg_icon                       TEXT,
    supported_model_types          TEXT         NOT NULL,
    credential_schema              TEXT         NOT NULL,
    default_parameter_rules        TEXT,
    implementation_key             VARCHAR(128) NOT NULL,
    default_base_url               VARCHAR(1024),
    source                         VARCHAR(32)  NOT NULL DEFAULT 'builtin',
    sort_order                     INT          NOT NULL DEFAULT 0,
    enabled                        BOOLEAN      NOT NULL DEFAULT TRUE,
    supports_remote_model_listing  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                     TIMESTAMP,
    updated_at                     TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_model_provider_tenant_name ON agent_model_provider (tenant_id, name);

-- Predefined model catalog per provider. `tenant_id='system'` is the shared
-- global catalog; a tenant may add its own entries visible only to itself.
-- Selection state (enabled / default) lives in the setting/default tables.
CREATE TABLE IF NOT EXISTS agent_predefined_model (
    id                VARCHAR(64)  NOT NULL,
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'system',
    provider_name     VARCHAR(255) NOT NULL,
    model             VARCHAR(255) NOT NULL,
    label             VARCHAR(255) NOT NULL,
    model_type        VARCHAR(32)  NOT NULL,
    features          TEXT,
    context_length    INT,
    dimensions        INT,
    parameter_rules   TEXT,
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_predef_provider_type ON agent_predefined_model (provider_name, model_type);
CREATE INDEX IF NOT EXISTS idx_predef_tenant_provider ON agent_predefined_model (tenant_id, provider_name);

CREATE TABLE IF NOT EXISTS agent_provider_credential (
    id               VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
    provider_name    VARCHAR(128) NOT NULL,
    credential_name  VARCHAR(128),
    encrypted_config TEXT,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP,
    PRIMARY KEY (id)
);

-- Tenant-registered custom models — only for user-added rows with model-level
-- overrides. Predefined catalog is NOT persisted here (Dify-parity).
CREATE TABLE IF NOT EXISTS agent_model (
    id               VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
    provider_name    VARCHAR(128) NOT NULL,
    model_name       VARCHAR(255) NOT NULL,
    model_type       VARCHAR(32)  NOT NULL,
    credential_id    VARCHAR(64),
    encrypted_config TEXT,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,  -- DEPRECATED: moved to agent_provider_model_setting
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE, -- DEPRECATED: moved to agent_tenant_default_model
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP,
    PRIMARY KEY (id)
);

-- Enable/disable per model per tenant. Missing row = enabled (Dify convention).
CREATE TABLE IF NOT EXISTS agent_provider_model_setting (
    id                     VARCHAR(64)  NOT NULL,
    tenant_id              VARCHAR(64)  NOT NULL DEFAULT 'default',
    provider_name          VARCHAR(128) NOT NULL,
    model_name             VARCHAR(255) NOT NULL,
    model_type             VARCHAR(32)  NOT NULL,
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    load_balancing_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMP,
    updated_at             TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_setting_lookup
    ON agent_provider_model_setting (tenant_id, provider_name, model_name, model_type);

-- Tenant default per model type (Dify parity for tenant_default_models).
CREATE TABLE IF NOT EXISTS agent_tenant_default_model (
    id             VARCHAR(64)  NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL DEFAULT 'default',
    provider_name  VARCHAR(128) NOT NULL,
    model_name     VARCHAR(255) NOT NULL,
    model_type     VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_default_tenant_type ON agent_tenant_default_model (tenant_id, model_type);

CREATE INDEX IF NOT EXISTS idx_agent_model_tenant_type ON agent_model (tenant_id, model_type);
CREATE INDEX IF NOT EXISTS idx_agent_cred_tenant_provider ON agent_provider_credential (tenant_id, provider_name);

CREATE TABLE IF NOT EXISTS agent_prompt_template (
    id           VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'default',
    name         VARCHAR(255) NOT NULL,
    category     VARCHAR(64),
    description  VARCHAR(1024),
    content      TEXT         NOT NULL,
    tags_json    TEXT,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_prompt_tenant_category ON agent_prompt_template (tenant_id, category);


-- ============================================================================
--  KNOWLEDGE MODULE (aligned with spring-agent-start table names for Dify parity)
--    · dataset               — knowledge bases (was agent_dataset)
--    · documents             — one row per uploaded doc (was agent_knowledge_document)
--    · document_segments     — chunks / embed units (was agent_segment)
--    · embeddings            — JDBC vector-store fallback rows (was agent_vector)
--    · dataset_query         — retrieval + dry-run history (was agent_dataset_hit_test_log)
-- ============================================================================

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
    document_count        INTEGER DEFAULT 0,
    segment_count         INTEGER DEFAULT 0,
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
    word_count    INTEGER,
    segment_count INTEGER,
    enabled       BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    PRIMARY KEY (id)
);

-- Sidecar for the async ingest queue (opt-in via
-- spring-agent.knowledge.async.enabled=true). Presence of a row = still
-- in-flight; the worker deletes it on COMPLETED. Kept separate from
-- `documents` so `SELECT * FROM documents` on the card grid doesn't drag
-- multi-MB raw_text blobs.
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

CREATE TABLE IF NOT EXISTS document_segments (
    id            VARCHAR(64)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
    dataset_id    VARCHAR(64)  NOT NULL,
    document_id   VARCHAR(64)  NOT NULL,
    position      INTEGER,
    content       TEXT,
    token_count   INTEGER,
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

CREATE TABLE IF NOT EXISTS embeddings (
    id            VARCHAR(64) NOT NULL,
    dataset_id    VARCHAR(64) NOT NULL,
    content       TEXT,
    metadata_json TEXT,
    embedding     TEXT,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_doc_dataset       ON documents (dataset_id);
CREATE INDEX IF NOT EXISTS idx_segment_dataset   ON document_segments (dataset_id);
CREATE INDEX IF NOT EXISTS idx_segment_document  ON document_segments (document_id);
CREATE INDEX IF NOT EXISTS idx_embeddings_dataset ON embeddings (dataset_id);

CREATE TABLE IF NOT EXISTS dataset_query (
    id           VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    dataset_id   VARCHAR(64) NOT NULL,
    query        TEXT,
    method       VARCHAR(32),
    top_k        INTEGER,
    results_json TEXT,
    hit_count    INTEGER,
    latency_ms   INTEGER,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_dataset_query_dataset ON dataset_query (dataset_id);


-- ============================================================================
--  WORKFLOW MODULE (aligned with spring-agent-start table names)
--    · workflows       — persisted graph definitions (was agent_workflow)
--    · workflow_runs   — one row per execution (was agent_workflow_run)
-- ============================================================================

-- workflows carries app-scoped drafts + published snapshots (Dify parity).
-- Invariant: the draft row's primary key equals the owning app's id, and its
-- version stays 'draft' forever. Publishing copies the graph into a new row
-- with a fresh id + timestamp-shaped version; the draft row is never replaced,
-- so subsequent edits always know where to write.
CREATE TABLE IF NOT EXISTS workflows (
    id                      VARCHAR(64)  NOT NULL,
    tenant_id               VARCHAR(64)  NOT NULL DEFAULT 'default',
    app_id                  VARCHAR(64),
    name                    VARCHAR(255),
    description             TEXT,
    mode                    VARCHAR(32),
    graph                   TEXT,
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


-- ============================================================================
--  AGENT MODULE (Dify parity — 智能体应用 as `apps`)
--    · apps        — persisted agent/app config (was agent_definition)
--    · messages    — chat history rows (was agent_chat_message)
-- ============================================================================

CREATE TABLE IF NOT EXISTS apps (
    id                        VARCHAR(64)  NOT NULL,
    tenant_id                 VARCHAR(64)  NOT NULL DEFAULT 'default',
    name                      VARCHAR(255) NOT NULL,
    description               VARCHAR(1024),
    icon                      VARCHAR(64),
    icon_background           VARCHAR(32),
    icon_type                 VARCHAR(32),
    use_icon_as_answer_icon   BOOLEAN DEFAULT FALSE,
    mode                      VARCHAR(32) DEFAULT 'agent',
    status                    VARCHAR(32) DEFAULT 'normal',
    is_public                 BOOLEAN DEFAULT FALSE,
    enable_site               BOOLEAN DEFAULT FALSE,
    enable_api                BOOLEAN DEFAULT FALSE,
    api_rpm                   INTEGER DEFAULT 0,
    api_rph                   INTEGER DEFAULT 0,
    instructions              TEXT,
    pre_prompt                TEXT,
    prompt_type               VARCHAR(32) DEFAULT 'simple',
    opening_statement         TEXT,
    suggested_questions_json  TEXT,
    user_input_form_json      TEXT,
    file_upload_json          TEXT,
    dataset_ids_json          TEXT,
    retrieval_config_json     TEXT,
    -- FK to workflows.id — the DRAFT workflow this app edits (id == app.id
    -- invariant, see workflows table comment). Runtime consumers follow
    -- published snapshots via the same field once a publish has happened.
    workflow_id               VARCHAR(64),
    model_id                  VARCHAR(64),
    strategy                  VARCHAR(32),
    tool_names_json           TEXT,
    approval_tools_json       TEXT,
    delegate_agent_ids_json   TEXT,
    max_iterations            INTEGER,
    memory_enabled            BOOLEAN DEFAULT TRUE,
    memory_window             INTEGER,
    published                 BOOLEAN DEFAULT TRUE,
    created_at                TIMESTAMP,
    updated_at                TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_apps_tenant_mode ON apps (tenant_id, mode);
CREATE INDEX IF NOT EXISTS idx_apps_published ON apps (published);

CREATE TABLE IF NOT EXISTS messages (
    id              VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    conversation_id VARCHAR(64) NOT NULL,
    agent_id        VARCHAR(64),
    role            VARCHAR(16),
    content         TEXT,
    seq             BIGINT,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_chat_conversation ON messages (conversation_id, seq);
CREATE INDEX IF NOT EXISTS idx_chat_agent_conv   ON messages (agent_id, conversation_id);

CREATE TABLE IF NOT EXISTS app_annotations (
    id         VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id     VARCHAR(64) NOT NULL,
    question   TEXT,
    content    TEXT,
    hit_count  INTEGER DEFAULT 0,
    enabled    BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_annotation_app ON app_annotations (app_id);

-- Per-app annotation retrieval configuration (score threshold + embedding
-- model). Effectively singleton per app; upsert by app_id.
CREATE TABLE IF NOT EXISTS app_annotation_settings (
    id                 VARCHAR(64) NOT NULL,
    tenant_id          VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id             VARCHAR(64) NOT NULL,
    score_threshold    REAL,
    embedding_model_id VARCHAR(64),
    enabled            BOOLEAN DEFAULT FALSE,
    created_at         TIMESTAMP,
    updated_at         TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_annotation_setting_app ON app_annotation_settings (app_id);

-- Chat sessions grouping messages under an app.
CREATE TABLE IF NOT EXISTS conversations (
    id                VARCHAR(64) NOT NULL,
    tenant_id         VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id            VARCHAR(64) NOT NULL,
    name              VARCHAR(255),
    summary           TEXT,
    introduction      TEXT,
    from_source       VARCHAR(32),
    from_end_user_id  VARCHAR(64),
    from_account_id   VARCHAR(64),
    status            VARCHAR(32) DEFAULT 'normal',
    pinned            BOOLEAN DEFAULT FALSE,
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_conversation_app ON conversations (app_id);

-- Per-app API access tokens.
CREATE TABLE IF NOT EXISTS api_tokens (
    id           VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id       VARCHAR(64) NOT NULL,
    type         VARCHAR(32) DEFAULT 'app',
    name         VARCHAR(255),
    token        VARCHAR(128) NOT NULL,
    last_used_at TIMESTAMP,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_api_token_app   ON api_tokens (app_id);
CREATE INDEX IF NOT EXISTS idx_api_token_value ON api_tokens (token);

-- Published widget / hosted-site config for an app. Singleton per app.
CREATE TABLE IF NOT EXISTS app_sites (
    id                        VARCHAR(64) NOT NULL,
    tenant_id                 VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id                    VARCHAR(64) NOT NULL,
    title                     VARCHAR(255),
    icon                      VARCHAR(64),
    icon_background           VARCHAR(32),
    icon_type                 VARCHAR(32),
    description               TEXT,
    default_language          VARCHAR(16),
    copyright                 VARCHAR(255),
    privacy_policy            TEXT,
    custom_disclaimer         TEXT,
    code                      VARCHAR(32),
    chat_color_theme          TEXT,
    chat_color_theme_inverted BOOLEAN DEFAULT FALSE,
    show_workflow_steps       BOOLEAN DEFAULT FALSE,
    use_icon_as_answer_icon   BOOLEAN DEFAULT FALSE,
    status                    VARCHAR(32) DEFAULT 'normal',
    created_at                TIMESTAMP,
    updated_at                TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_app_site_app  ON app_sites (app_id);
CREATE INDEX IF NOT EXISTS idx_app_site_code ON app_sites (code);

-- Tenant-scoped organisational tags applied to apps or datasets.
CREATE TABLE IF NOT EXISTS tags (
    id         VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL DEFAULT 'default',
    type       VARCHAR(32) DEFAULT 'app',
    name       VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_tag_tenant_type ON tags (tenant_id, type);

CREATE TABLE IF NOT EXISTS tag_bindings (
    id          VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    tag_id      VARCHAR(64) NOT NULL,
    target_id   VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) DEFAULT 'app',
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_tag_binding_target ON tag_bindings (target_id, target_type);
CREATE INDEX IF NOT EXISTS idx_tag_binding_tag    ON tag_bindings (tag_id);


-- ============================================================================
--  TRIGGER MODULE (aligned with spring-agent-start table names)
--    · app_triggers          — webhook / cron / event triggers (was agent_trigger)
--    · trigger_invocations   — every fire recorded here (was agent_trigger_invocation)
-- ============================================================================

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

CREATE INDEX IF NOT EXISTS idx_trigger_type          ON app_triggers (type, enabled);
CREATE INDEX IF NOT EXISTS idx_invocation_trigger    ON trigger_invocations (trigger_id);


-- ============================================================================
--  OBSERVABILITY MODULE
--    · llm_calls — per-LLM-call metrics (tokens / cost / latency); was agent_llm_call
-- ============================================================================

CREATE TABLE IF NOT EXISTS llm_calls (
    id                VARCHAR(64) NOT NULL,
    tenant_id         VARCHAR(64) NOT NULL DEFAULT 'default',
    provider          VARCHAR(128),
    model             VARCHAR(255),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    cost_micros       BIGINT,
    latency_ms        BIGINT,
    success           BOOLEAN,
    error_type        VARCHAR(255),
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_llm_call_model    ON llm_calls (model);
CREATE INDEX IF NOT EXISTS idx_llm_call_created  ON llm_calls (created_at);


-- ============================================================================
--  Done. If you want pgvector for embeddings instead of the built-in JDBC store:
--    1. CREATE EXTENSION IF NOT EXISTS vector;
--    2. Add agent-start-store-pgvector to your pom.
--    3. Set spring-agent.knowledge.vector-store=pgvector.
--    Each dataset then gets its own {dataset_id}-scoped pgvector table.
-- ============================================================================
