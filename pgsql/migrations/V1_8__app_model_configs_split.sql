-- ============================================================================
-- Migration V1.8 . split apps behaviour into app_model_configs
-- ----------------------------------------------------------------------------
-- Dify-parity restructure. The apps table becomes a lean catalog row (name /
-- icon / mode / publish state / denormalised model reference); everything
-- about how the app *behaves* moves into a 1:1 sidecar keyed by app id.
--
-- Rationale
--   * Workflow / chatflow apps already carry prompt / model / retrieval
--     inside the workflows.graph blob. Duplicating those on apps forces
--     every save path to keep two copies in sync.
--   * The agent-list card only needs name / icon / mode / published — so
--     the drawer payload (prompt / model overrides / tools / retrieval)
--     was bloating a hot list-view read for no reason.
--   * Restore-from-history and copy-app become one-row swaps of the
--     sidecar, without touching the catalog row.
--
-- Migration steps
--   1. CREATE TABLE app_model_configs.
--   2. Backfill one sidecar row per existing app by copying the columns we
--      are about to drop.
--   3. Drop the migrated columns from apps (plus the transient
--      apps.model_settings_json from V1.7 which is now app_model_configs.configs).
--
-- Idempotent: the CREATE TABLE / ADD COLUMN / DROP COLUMN clauses use IF (NOT)
-- EXISTS so a partial replay is safe.
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_model_configs (
    id                                VARCHAR(64) NOT NULL,
    tenant_id                         VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id                            VARCHAR(64) NOT NULL,
    model_provider                    VARCHAR(64),
    model_name                        VARCHAR(128),
    model_json                        TEXT,
    configs                           TEXT,
    pre_prompt                        TEXT,
    prompt_type                       VARCHAR(32) DEFAULT 'simple',
    chat_prompt_config                TEXT,
    completion_prompt_config          TEXT,
    opening_statement                 TEXT,
    suggested_questions_json          TEXT,
    suggested_questions_after_answer  TEXT,
    more_like_this                    TEXT,
    user_input_form_json              TEXT,
    agent_mode                        TEXT,
    strategy                          VARCHAR(32),
    tool_names_json                   TEXT,
    approval_tools_json               TEXT,
    delegate_agent_ids_json           TEXT,
    max_iterations                    INTEGER,
    memory_enabled                    BOOLEAN DEFAULT TRUE,
    memory_window                     INTEGER,
    dataset_ids_json                  TEXT,
    dataset_configs_json              TEXT,
    file_upload_json                  TEXT,
    external_data_tools               TEXT,
    retriever_resource                TEXT,
    dataset_query_variable            VARCHAR(255),
    speech_to_text                    TEXT,
    text_to_speech                    TEXT,
    sensitive_word_avoidance          TEXT,
    created_at                        TIMESTAMP,
    updated_at                        TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_app_model_config_app ON app_model_configs (app_id);

-- Backfill: one sidecar row per app. Uses COALESCE(instructions, pre_prompt)
-- because early rows filled instructions, later rows filled pre_prompt.
INSERT INTO app_model_configs (
    id, tenant_id, app_id,
    model_provider, model_name,
    configs,
    pre_prompt, prompt_type, opening_statement, suggested_questions_json,
    user_input_form_json,
    strategy, tool_names_json, approval_tools_json, delegate_agent_ids_json,
    max_iterations, memory_enabled, memory_window,
    dataset_ids_json, dataset_configs_json, file_upload_json,
    created_at, updated_at
)
SELECT
    a.id, a.tenant_id, a.id,
    a.model_provider, a.model_name,
    a.model_settings_json,
    COALESCE(a.pre_prompt, a.instructions),
    COALESCE(a.prompt_type, 'simple'),
    a.opening_statement, a.suggested_questions_json,
    a.user_input_form_json,
    a.strategy, a.tool_names_json, a.approval_tools_json, a.delegate_agent_ids_json,
    a.max_iterations, a.memory_enabled, a.memory_window,
    a.dataset_ids_json, a.retrieval_config_json, a.file_upload_json,
    a.created_at, a.updated_at
FROM apps a
WHERE NOT EXISTS (SELECT 1 FROM app_model_configs c WHERE c.id = a.id);

-- Drop the migrated columns from apps. Kept: id, tenant_id, name, description,
-- icon(_background|_type), use_icon_as_answer_icon, mode, status, is_public,
-- enable_(site|api), api_(rpm|rph), published, workflow_id, model_(name|provider),
-- created_at, updated_at.
ALTER TABLE apps DROP COLUMN IF EXISTS instructions;
ALTER TABLE apps DROP COLUMN IF EXISTS pre_prompt;
ALTER TABLE apps DROP COLUMN IF EXISTS prompt_type;
ALTER TABLE apps DROP COLUMN IF EXISTS opening_statement;
ALTER TABLE apps DROP COLUMN IF EXISTS suggested_questions_json;
ALTER TABLE apps DROP COLUMN IF EXISTS user_input_form_json;
ALTER TABLE apps DROP COLUMN IF EXISTS file_upload_json;
ALTER TABLE apps DROP COLUMN IF EXISTS dataset_ids_json;
ALTER TABLE apps DROP COLUMN IF EXISTS retrieval_config_json;
ALTER TABLE apps DROP COLUMN IF EXISTS strategy;
ALTER TABLE apps DROP COLUMN IF EXISTS tool_names_json;
ALTER TABLE apps DROP COLUMN IF EXISTS approval_tools_json;
ALTER TABLE apps DROP COLUMN IF EXISTS delegate_agent_ids_json;
ALTER TABLE apps DROP COLUMN IF EXISTS max_iterations;
ALTER TABLE apps DROP COLUMN IF EXISTS memory_enabled;
ALTER TABLE apps DROP COLUMN IF EXISTS memory_window;
ALTER TABLE apps DROP COLUMN IF EXISTS model_settings_json;
