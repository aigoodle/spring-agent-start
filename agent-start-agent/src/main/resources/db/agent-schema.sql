-- agent-start-agent schema (portable across H2 and MySQL/Postgres).
-- Table names aligned with spring-agent-start (Dify parity):
--   apps                    ← was agent_definition (智能体应用) — lean metadata
--   app_model_configs       ← 1:1 sidecar carrying prompt / model params /
--                              retrieval / agent behaviour (Dify parity)
--   messages                ← was agent_chat_message
--   app_annotations         ← per-app QA overrides
--   app_annotation_settings ← retrieval config for annotations
--   conversations           ← chat sessions grouping messages under an app
--   api_tokens              ← per-app API access tokens
--   app_sites               ← published widget / hosted site config
--   tags / tag_bindings     ← tenant-scoped organisational tags
--
-- Design note: the split between `apps` and `app_model_configs` follows Dify —
-- `apps` is the at-a-glance catalog row (name / icon / mode / publish state),
-- everything about *how the app behaves* (prompt, model overrides, tools,
-- retrieval) lives in the sidecar keyed by app id. Workflow / chatflow apps
-- carry an empty sidecar because their behaviour lives in the workflow graph.

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
    api_rpm                   INT DEFAULT 0,
    api_rph                   INT DEFAULT 0,
    published                 BOOLEAN DEFAULT TRUE,
    -- FK to workflows.id — the persistent DRAFT workflow this app edits (Dify
    -- parity). Populated on create for workflow/chatflow modes; null for
    -- chat/agent/completion.
    workflow_id               VARCHAR(64),
    -- Denormalised model reference kept on the catalog row so the agent-list
    -- card can render "provider · model" without a JOIN. The source of truth
    -- for runtime resolution is app_model_configs.
    model_name                VARCHAR(128),
    model_provider            VARCHAR(64),
    created_at                TIMESTAMP,
    updated_at                TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_apps_tenant_mode ON apps (tenant_id, mode);
CREATE INDEX IF NOT EXISTS idx_apps_published ON apps (published);

-- ============================================================================
-- app_model_configs — 1:1 sidecar with apps.id (id == app_id). Carries the
-- entire "编排" drawer payload: system prompt, model overrides, agent
-- strategy / tools / delegation / memory, dataset / retrieval config, user
-- input form and speech / moderation blobs. Vendor-neutral: thinking mode is
-- a normalized 'auto'|'enabled'|'disabled' flag inside `configs`, translated
-- per-vendor by AgentChatOptionsFactory.
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_model_configs (
    id                                VARCHAR(64) NOT NULL,
    tenant_id                         VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id                            VARCHAR(64) NOT NULL,
    -- Selected model
    model_provider                    VARCHAR(64),
    model_name                        VARCHAR(128),
    model_json                        TEXT,
    configs                           TEXT,
    -- Prompt
    pre_prompt                        TEXT,
    prompt_type                       VARCHAR(32) DEFAULT 'simple',
    chat_prompt_config                TEXT,
    completion_prompt_config          TEXT,
    -- Chat presentation
    opening_statement                 TEXT,
    suggested_questions_json          TEXT,
    suggested_questions_after_answer  TEXT,
    more_like_this                    TEXT,
    user_input_form_json              TEXT,
    -- Agent behaviour
    agent_mode                        TEXT,
    strategy                          VARCHAR(32),
    tool_names_json                   TEXT,
    approval_tools_json               TEXT,
    delegate_agent_ids_json           TEXT,
    max_iterations                    INT,
    memory_enabled                    BOOLEAN DEFAULT TRUE,
    memory_window                     INT,
    -- Knowledge / RAG
    dataset_ids_json                  TEXT,
    dataset_configs_json              TEXT,
    file_upload_json                  TEXT,
    external_data_tools               TEXT,
    retriever_resource                TEXT,
    dataset_query_variable            VARCHAR(255),
    -- Speech / moderation
    speech_to_text                    TEXT,
    text_to_speech                    TEXT,
    sensitive_word_avoidance          TEXT,
    created_at                        TIMESTAMP,
    updated_at                        TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_app_model_config_app ON app_model_configs (app_id);

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
CREATE INDEX IF NOT EXISTS idx_chat_agent_conv ON messages (agent_id, conversation_id);

-- User-authored QA overrides surfaced in the "日志与标注" drawer tab. When a
-- chat query hits `question`, `content` is returned verbatim (bypassing the
-- LLM). Ranking + hit-count bump is wired in a follow-up pass.
CREATE TABLE IF NOT EXISTS app_annotations (
    id         VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id     VARCHAR(64) NOT NULL,
    question   TEXT,
    content    TEXT,
    hit_count  INT DEFAULT 0,
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
    score_threshold    FLOAT,
    embedding_model_id VARCHAR(64),
    enabled            BOOLEAN DEFAULT FALSE,
    created_at         TIMESTAMP,
    updated_at         TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_annotation_setting_app ON app_annotation_settings (app_id);

-- Chat sessions grouping messages under an app. The messages table already
-- holds conversation_id — this row carries user-visible metadata.
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

-- Per-app API access tokens. Value is generated server-side on create.
CREATE TABLE IF NOT EXISTS api_tokens (
    id            VARCHAR(64) NOT NULL,
    tenant_id     VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id        VARCHAR(64) NOT NULL,
    type          VARCHAR(32) DEFAULT 'app',
    name          VARCHAR(255),
    token         VARCHAR(128) NOT NULL,
    last_used_at  TIMESTAMP,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_api_token_app ON api_tokens (app_id);
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
CREATE INDEX IF NOT EXISTS idx_app_site_app ON app_sites (app_id);
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
    id           VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    tag_id       VARCHAR(64) NOT NULL,
    target_id    VARCHAR(64) NOT NULL,
    target_type  VARCHAR(32) DEFAULT 'app',
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_tag_binding_target ON tag_bindings (target_id, target_type);
CREATE INDEX IF NOT EXISTS idx_tag_binding_tag ON tag_bindings (tag_id);
