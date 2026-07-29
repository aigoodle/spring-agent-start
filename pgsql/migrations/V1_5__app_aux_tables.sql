-- ============================================================================
-- Migration V1.5 · Legacy "app附属表" ported to spring-agent-start
-- ----------------------------------------------------------------------------
-- Migrates the auxiliary tables that hung off the legacy `apps` row in
-- spring-agent-start (Dify parity) so the console can drive:
--   * per-app knowledge attachment          (dataset_ids_json + retrieval_config_json on apps)
--   * per-app icon / status / api toggles   (new columns on apps)
--   * per-app prompt config                 (pre_prompt / prompt_type / user_input_form_json / file_upload_json)
--   * chat sessions                         (conversations)
--   * annotation retrieval                  (app_annotation_settings)
--   * public API tokens                     (api_tokens)
--   * hosted widget / site                  (app_sites)
--   * organisational tags                   (tags + tag_bindings)
--
-- Design decision — flatter than the legacy schema:
--   The legacy code split app-level config across `apps` + `app_model_configs`
--   with big JSON blobs on the child table. We keep the JSON but attach it
--   directly to `apps` — one row per app is the natural aggregate root and
--   the split bought nothing except an extra join.
--
-- Idempotent — safe to run twice via ADD COLUMN IF NOT EXISTS / CREATE TABLE
-- IF NOT EXISTS. Runs in a single transaction so partial failures roll back.
-- ============================================================================

BEGIN;

-- ─── apps · Dify parity columns ─────────────────────────────────────────────
ALTER TABLE apps ADD COLUMN IF NOT EXISTS icon_type               VARCHAR(32);
ALTER TABLE apps ADD COLUMN IF NOT EXISTS use_icon_as_answer_icon BOOLEAN DEFAULT FALSE;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS status                  VARCHAR(32) DEFAULT 'normal';
ALTER TABLE apps ADD COLUMN IF NOT EXISTS is_public               BOOLEAN DEFAULT FALSE;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS enable_site             BOOLEAN DEFAULT FALSE;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS enable_api              BOOLEAN DEFAULT FALSE;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS api_rpm                 INTEGER DEFAULT 0;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS api_rph                 INTEGER DEFAULT 0;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS pre_prompt              TEXT;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS prompt_type             VARCHAR(32) DEFAULT 'simple';
ALTER TABLE apps ADD COLUMN IF NOT EXISTS user_input_form_json    TEXT;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS file_upload_json        TEXT;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS retrieval_config_json   TEXT;

-- ─── app_annotation_settings ────────────────────────────────────────────────
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

-- ─── conversations ──────────────────────────────────────────────────────────
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

-- ─── api_tokens ─────────────────────────────────────────────────────────────
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

-- ─── app_sites ──────────────────────────────────────────────────────────────
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

-- ─── tags + tag_bindings ────────────────────────────────────────────────────
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

COMMIT;
