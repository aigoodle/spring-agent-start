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
--    · goodle_model_provider         — provider definitions (DB-driven, seeded from Java built-ins)
--    · goodle_predefined_model       — provider catalog (DB-driven, replaces Java predefinedModels())
--    · goodle_provider_credential    — encrypted per-tenant credentials
--    · goodle_model                  — per-tenant CUSTOM model registrations (with overrides)
--    · goodle_provider_model_setting — per-tenant model enable/disable (missing row = enabled)
--    · goodle_tenant_default_model   — per-tenant default per model type
--    · goodle_prompt_template        — reusable prompt templates
-- ============================================================================

-- Provider definitions. `source='builtin'` rows are seeded from Java at first boot.
-- `source='external'|'custom'` can be added via /api/v1/model-provider-definitions
-- without touching Java code — supporting the "extend supported providers via DB
-- rather than code" flow.
CREATE TABLE IF NOT EXISTS goodle_model_provider (
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
CREATE INDEX IF NOT EXISTS idx_model_provider_tenant_name ON goodle_model_provider (tenant_id, name);

-- Predefined model catalog per provider. `tenant_id='system'` is the shared
-- global catalog; a tenant may add its own entries visible only to itself.
-- Selection state (enabled / default) lives in the setting/default tables.
CREATE TABLE IF NOT EXISTS goodle_predefined_model (
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
CREATE INDEX IF NOT EXISTS idx_predef_provider_type ON goodle_predefined_model (provider_name, model_type);
CREATE INDEX IF NOT EXISTS idx_predef_tenant_provider ON goodle_predefined_model (tenant_id, provider_name);

CREATE TABLE IF NOT EXISTS goodle_provider_credential (
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
CREATE TABLE IF NOT EXISTS goodle_model (
    id               VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
    provider_name    VARCHAR(128) NOT NULL,
    model_name       VARCHAR(255) NOT NULL,
    model_type       VARCHAR(32)  NOT NULL,
    credential_id    VARCHAR(64),
    encrypted_config TEXT,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,  -- DEPRECATED: moved to goodle_provider_model_setting
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE, -- DEPRECATED: moved to goodle_tenant_default_model
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP,
    PRIMARY KEY (id)
);

-- Enable/disable per model per tenant. Missing row = enabled (Dify convention).
CREATE TABLE IF NOT EXISTS goodle_provider_model_setting (
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
    ON goodle_provider_model_setting (tenant_id, provider_name, model_name, model_type);

-- Tenant default per model type (Dify parity for tenant_default_models).
CREATE TABLE IF NOT EXISTS goodle_tenant_default_model (
    id             VARCHAR(64)  NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL DEFAULT 'default',
    provider_name  VARCHAR(128) NOT NULL,
    model_name     VARCHAR(255) NOT NULL,
    model_type     VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_default_tenant_type ON goodle_tenant_default_model (tenant_id, model_type);

CREATE INDEX IF NOT EXISTS idx_agent_model_tenant_type ON goodle_model (tenant_id, model_type);
CREATE INDEX IF NOT EXISTS idx_agent_cred_tenant_provider ON goodle_provider_credential (tenant_id, provider_name);

CREATE TABLE IF NOT EXISTS goodle_prompt_template (
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

CREATE INDEX IF NOT EXISTS idx_prompt_tenant_category ON goodle_prompt_template (tenant_id, category);


-- ============================================================================
--  KNOWLEDGE MODULE (aligned with spring-agent-start table names for Dify parity)
--    · goodle_dataset               — knowledge bases (was agent_dataset)
--    · goodle_documents             — one row per uploaded doc (was agent_knowledge_document)
--    · goodle_document_segments     — chunks / embed units (was agent_segment)
--    · goodle_embeddings            — JDBC vector-store fallback rows (was agent_vector)
--    · goodle_dataset_query         — retrieval + dry-run history (was agent_dataset_hit_test_log)
-- ============================================================================

CREATE TABLE IF NOT EXISTS goodle_dataset (
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

CREATE TABLE IF NOT EXISTS goodle_documents (
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
-- `goodle_documents` so `SELECT * FROM goodle_documents` on the card grid doesn't drag
-- multi-MB raw_text blobs.
CREATE TABLE IF NOT EXISTS goodle_document_ingest_queue (
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
CREATE INDEX IF NOT EXISTS idx_ingest_queue_dataset ON goodle_document_ingest_queue (dataset_id);

CREATE TABLE IF NOT EXISTS goodle_document_segments (
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

CREATE TABLE IF NOT EXISTS goodle_embeddings (
    id            VARCHAR(64) NOT NULL,
    dataset_id    VARCHAR(64) NOT NULL,
    content       TEXT,
    metadata_json TEXT,
    embedding     TEXT,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_doc_dataset       ON goodle_documents (dataset_id);
CREATE INDEX IF NOT EXISTS idx_segment_dataset   ON goodle_document_segments (dataset_id);
CREATE INDEX IF NOT EXISTS idx_segment_document  ON goodle_document_segments (document_id);
CREATE INDEX IF NOT EXISTS idx_embeddings_dataset ON goodle_embeddings (dataset_id);

CREATE TABLE IF NOT EXISTS goodle_dataset_query (
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

CREATE INDEX IF NOT EXISTS idx_dataset_query_dataset ON goodle_dataset_query (dataset_id);


-- ============================================================================
--  WORKFLOW MODULE (aligned with spring-agent-start table names)
--    · goodle_workflows       — persisted graph definitions (was agent_workflow)
--    · goodle_workflow_runs   — one row per execution (was agent_workflow_run)
-- ============================================================================

-- goodle_workflows carries app-scoped drafts + published snapshots (Dify parity).
-- Invariant: the draft row's primary key equals the owning app's id, and its
-- version stays 'draft' forever. Publishing copies the graph into a new row
-- with a fresh id + timestamp-shaped version; the draft row is never replaced,
-- so subsequent edits always know where to write.
CREATE TABLE IF NOT EXISTS goodle_workflows (
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

CREATE INDEX IF NOT EXISTS idx_workflow_app ON goodle_workflows (app_id, version);
CREATE INDEX IF NOT EXISTS idx_workflow_tenant ON goodle_workflows (tenant_id);

CREATE TABLE IF NOT EXISTS goodle_workflow_runs (
    id              VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    workflow_id     VARCHAR(64),
    conversation_id VARCHAR(255),
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

CREATE INDEX IF NOT EXISTS idx_run_workflow ON goodle_workflow_runs (workflow_id);
ALTER TABLE goodle_workflow_runs ALTER COLUMN conversation_id TYPE VARCHAR(255);

CREATE TABLE IF NOT EXISTS goodle_workflow_checkpoints (
    id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL DEFAULT 'default', run_id VARCHAR(64) NOT NULL,
    workflow_id VARCHAR(64), graph_version VARCHAR(64), graph_json TEXT NOT NULL, inputs_json TEXT,
    conversation_id VARCHAR(255), status VARCHAR(32) NOT NULL,
    node_states_json TEXT, variable_pool_json TEXT, branch_results_json TEXT, iteration_cursors_json TEXT,
    pending_nodes_json TEXT, resume_node_id VARCHAR(255), interrupt_reason TEXT,
    checkpoint_version BIGINT NOT NULL DEFAULT 0, lease_owner VARCHAR(255), lease_expires_at TIMESTAMP,
    wait_type VARCHAR(64), correlation_key VARCHAR(255), resume_token_hash VARCHAR(128), input_schema_json TEXT,
    wait_expires_at TIMESTAMP, wake_at TIMESTAMP, resumed_by VARCHAR(255), resumed_at TIMESTAMP,
    created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS idx_workflow_checkpoint_lease ON goodle_workflow_checkpoints (status, lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_workflow_checkpoint_correlation ON goodle_workflow_checkpoints (tenant_id, correlation_key, status);
ALTER TABLE goodle_workflow_checkpoints ALTER COLUMN conversation_id TYPE VARCHAR(255);

CREATE TABLE IF NOT EXISTS goodle_workflow_run_nodes (
    id VARCHAR(255) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL DEFAULT 'default', run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(255) NOT NULL, node_type VARCHAR(64), status VARCHAR(32) NOT NULL, attempt INTEGER NOT NULL DEFAULT 0,
    selected_handle VARCHAR(255), outputs_json TEXT, error TEXT, idempotency_key VARCHAR(255),
    execution_mode VARCHAR(32), result_cache_policy VARCHAR(32), resumable BOOLEAN,
    started_at TIMESTAMP, finished_at TIMESTAMP, executor_instance VARCHAR(255),
    token_count BIGINT, cost VARCHAR(64), external_status INTEGER, trace_id VARCHAR(128), span_id VARCHAR(128),
    checkpoint_version BIGINT,
    created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE (tenant_id, run_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_workflow_run_node_run ON goodle_workflow_run_nodes (tenant_id, run_id, status);

CREATE TABLE IF NOT EXISTS goodle_workflow_execution_events (
    id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL DEFAULT 'default', run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(255), event_type VARCHAR(64) NOT NULL, attempt INTEGER, payload_json TEXT,
    input_summary TEXT, output_summary TEXT, token_count BIGINT, cost VARCHAR(64), external_status INTEGER,
    checkpoint_id VARCHAR(64), trace_id VARCHAR(128), span_id VARCHAR(128), executor_instance VARCHAR(255),
    created_at TIMESTAMP, updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_workflow_event_run ON goodle_workflow_execution_events (tenant_id, run_id, created_at);

CREATE TABLE IF NOT EXISTS goodle_workflow_resume_signals (
    id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL DEFAULT 'default', run_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(255) NOT NULL, correlation_key VARCHAR(255), payload_hash VARCHAR(128), resumed_by VARCHAR(255),
    created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE (tenant_id, event_id)
);
CREATE INDEX IF NOT EXISTS idx_workflow_signal_run ON goodle_workflow_resume_signals (tenant_id, run_id);

CREATE TABLE IF NOT EXISTS goodle_human_interactions (
    id VARCHAR(64) NOT NULL, tenant_id VARCHAR(64) NOT NULL DEFAULT 'default', workflow_id VARCHAR(64), app_id VARCHAR(64),
    run_id VARCHAR(64) NOT NULL, node_id VARCHAR(255) NOT NULL, conversation_id VARCHAR(255), status VARCHAR(32) NOT NULL,
    title VARCHAR(255), description TEXT, form_mode VARCHAR(32), presentation_mode VARCHAR(32), input_schema_json TEXT,
    access_token_hash VARCHAR(128) NOT NULL, delivery_config_json TEXT, submitted_values_json TEXT, submitted_text TEXT,
    submitted_by VARCHAR(255), short_code VARCHAR(16), channel_provider VARCHAR(64), channel_id VARCHAR(128),
    channel_connection_id VARCHAR(64), channel_target VARCHAR(255), channel_conversation_id VARCHAR(255),
    notification_message_id VARCHAR(255), expires_at TIMESTAMP, submitted_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP,
    PRIMARY KEY (id), UNIQUE (tenant_id, run_id, node_id), UNIQUE (access_token_hash)
);
CREATE INDEX IF NOT EXISTS idx_human_interaction_conversation ON goodle_human_interactions (tenant_id, conversation_id, created_at);
ALTER TABLE goodle_human_interactions ALTER COLUMN conversation_id TYPE VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_human_interaction_channel ON goodle_human_interactions
    (tenant_id, channel_connection_id, channel_target, status, created_at);


-- ============================================================================
--  AGENT MODULE (Dify parity — 智能体应用 as `goodle_apps`)
--    · goodle_apps        — persisted agent/app config (was agent_definition)
--    · goodle_messages    — chat history rows (was agent_chat_message)
-- ============================================================================

CREATE TABLE IF NOT EXISTS goodle_apps (
    id                        VARCHAR(64)  NOT NULL,
    tenant_id                 VARCHAR(64)  NOT NULL DEFAULT 'default',
    app_code                  VARCHAR(100),
    visibility                VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
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
    -- FK to goodle_workflows.id — the DRAFT workflow this app edits (id == app.id
    -- invariant, see goodle_workflows table comment). Runtime consumers follow
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

CREATE INDEX IF NOT EXISTS idx_apps_tenant_mode ON goodle_apps (tenant_id, mode);
CREATE INDEX IF NOT EXISTS idx_apps_published ON goodle_apps (published);
CREATE UNIQUE INDEX IF NOT EXISTS uk_apps_tenant_code ON goodle_apps (tenant_id, app_code);

CREATE TABLE IF NOT EXISTS goodle_messages (
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

CREATE INDEX IF NOT EXISTS idx_chat_conversation ON goodle_messages (conversation_id, seq);
CREATE INDEX IF NOT EXISTS idx_chat_agent_conv   ON goodle_messages (agent_id, conversation_id);

CREATE TABLE IF NOT EXISTS goodle_app_annotations (
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

CREATE INDEX IF NOT EXISTS idx_annotation_app ON goodle_app_annotations (app_id);

-- Per-app annotation retrieval configuration (score threshold + embedding
-- model). Effectively singleton per app; upsert by app_id.
CREATE TABLE IF NOT EXISTS goodle_app_annotation_settings (
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
CREATE INDEX IF NOT EXISTS idx_annotation_setting_app ON goodle_app_annotation_settings (app_id);

-- Chat sessions grouping goodle_messages under an app.
CREATE TABLE IF NOT EXISTS goodle_conversations (
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
CREATE INDEX IF NOT EXISTS idx_conversation_app ON goodle_conversations (app_id);

-- Per-app API access tokens.
CREATE TABLE IF NOT EXISTS goodle_api_tokens (
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
CREATE INDEX IF NOT EXISTS idx_api_token_app   ON goodle_api_tokens (app_id);
CREATE INDEX IF NOT EXISTS idx_api_token_value ON goodle_api_tokens (token);

-- Published widget / hosted-site config for an app. Singleton per app.
CREATE TABLE IF NOT EXISTS goodle_app_sites (
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
CREATE INDEX IF NOT EXISTS idx_app_site_app  ON goodle_app_sites (app_id);
CREATE INDEX IF NOT EXISTS idx_app_site_code ON goodle_app_sites (code);

-- Tenant-scoped organisational goodle_tags applied to goodle_apps or datasets.
CREATE TABLE IF NOT EXISTS goodle_tags (
    id         VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL DEFAULT 'default',
    type       VARCHAR(32) DEFAULT 'app',
    name       VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_tag_tenant_type ON goodle_tags (tenant_id, type);

CREATE TABLE IF NOT EXISTS goodle_tag_bindings (
    id          VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    tag_id      VARCHAR(64) NOT NULL,
    target_id   VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) DEFAULT 'app',
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_tag_binding_target ON goodle_tag_bindings (target_id, target_type);
CREATE INDEX IF NOT EXISTS idx_tag_binding_tag    ON goodle_tag_bindings (tag_id);


-- ============================================================================
--  TRIGGER MODULE (aligned with spring-agent-start table names)
--    · goodle_app_triggers          — webhook / cron / event triggers (was agent_trigger)
--    · goodle_trigger_invocations   — every fire recorded here (was agent_trigger_invocation)
-- ============================================================================

CREATE TABLE IF NOT EXISTS goodle_app_triggers (
    id          VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    name        VARCHAR(255),
    type        VARCHAR(32),
    enabled     BOOLEAN DEFAULT TRUE,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    config_json TEXT,
    next_fire_at TIMESTAMP,
    last_fire_at TIMESTAMP,
    lock_until TIMESTAMP,
    lock_owner VARCHAR(128),
    fire_count BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS goodle_trigger_invocations (
    id           VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    trigger_id   VARCHAR(64) NOT NULL,
    source       VARCHAR(32),
    conversation_id VARCHAR(128),
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

CREATE INDEX IF NOT EXISTS idx_trigger_type          ON goodle_app_triggers (type, enabled);
CREATE INDEX IF NOT EXISTS idx_trigger_due           ON goodle_app_triggers (enabled, type, next_fire_at);
CREATE INDEX IF NOT EXISTS idx_invocation_trigger    ON goodle_trigger_invocations (trigger_id);


-- ============================================================================
--  OBSERVABILITY MODULE
--    · goodle_llm_calls — per-LLM-call metrics (tokens / cost / latency); was agent_llm_call
-- ============================================================================

CREATE TABLE IF NOT EXISTS goodle_llm_calls (
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

CREATE INDEX IF NOT EXISTS idx_llm_call_model    ON goodle_llm_calls (model);
CREATE INDEX IF NOT EXISTS idx_llm_call_created  ON goodle_llm_calls (created_at);


-- ============================================================================
--  Done. If you want pgvector for goodle_embeddings instead of the built-in JDBC store:
--    1. CREATE EXTENSION IF NOT EXISTS vector;
--    2. Add agent-start-store-pgvector to your pom.
--    3. Set spring-agent.knowledge.vector-store=pgvector.
--    Each goodle_dataset then gets its own {dataset_id}-scoped pgvector table.
-- ============================================================================
