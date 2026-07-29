-- ============================================================================
-- Migration V1.3 · Split apps.graph_json into a proper workflows.app_id draft
--                  (Dify parity — one app → many workflows, one mutable draft)
-- ----------------------------------------------------------------------------
-- Before this migration:
--   * `apps.graph_json` inlined the visual-designer DAG on the app row, so
--     GET /agents returned the whole graph on every list call.
--   * `workflows` was a flat table with no link back to the owning app, no
--     draft/snapshot distinction (INTEGER version + published=true default),
--     no Dify-parity side-cars (features / env vars / conversation vars).
--
-- After:
--   * `apps.workflow_id` is a FK to `workflows.id` — points to the DRAFT for
--     workflow/chatflow modes (by convention `workflows.id == apps.id`), and
--     gets rebound to the latest published snapshot after each publish.
--   * `workflows.app_id` is the reverse FK. `version` becomes VARCHAR — 'draft'
--     for the mutable working copy, timestamp/label for immutable snapshots.
--   * `workflows` gains `features` / `environment_variables` /
--     `conversation_variables` / `output` / `marked_name` / `marked_comment`
--     JSON side-cars so downstream layers (feature toggles, structured output,
--     scoped env vars) can hang on without another rewrite.
--
-- Idempotent — safe to run twice via ADD COLUMN IF NOT EXISTS / DROP COLUMN
-- IF EXISTS. Runs in a single transaction so a partial failure rolls back.
-- ============================================================================

BEGIN;

-- ─── apps ────────────────────────────────────────────────────────────────────
-- The graph is now a workflow row, not an inlined column. Any rows that had
-- graph_json set were dev/scratch data; the frontend re-seeds the draft on
-- next open via the initGraph fallback, so dropping is safe. If you have real
-- data to preserve, hand-materialize it into workflows before running:
--
--   INSERT INTO workflows (id, tenant_id, app_id, name, mode, graph_json,
--                          version, published, created_at, updated_at)
--   SELECT id, tenant_id, id, name, mode, graph_json, 'draft', false,
--          created_at, updated_at
--     FROM apps
--    WHERE graph_json IS NOT NULL AND mode IN ('workflow', 'chatflow');
--   UPDATE apps SET workflow_id = id
--    WHERE mode IN ('workflow', 'chatflow') AND graph_json IS NOT NULL;

ALTER TABLE apps ADD COLUMN IF NOT EXISTS workflow_id VARCHAR(64);
ALTER TABLE apps DROP COLUMN IF EXISTS graph_json;

CREATE INDEX IF NOT EXISTS idx_apps_workflow ON apps (workflow_id);

-- ─── workflows ──────────────────────────────────────────────────────────────
-- Change `version` from INTEGER to VARCHAR so 'draft' / '1.0' / timestamp
-- labels all fit in the same column. Postgres needs USING to cast existing
-- rows; the default flips to 'draft' at the same time.
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS app_id                 VARCHAR(64);
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS features               TEXT;
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS environment_variables  TEXT;
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS conversation_variables TEXT;
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS output                 TEXT;
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS marked_name            VARCHAR(255);
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS marked_comment         VARCHAR(1024);

-- The next 3 statements are wrapped in a DO block so re-running is a no-op
-- (idempotent) even after the column type has already been changed.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'workflows' AND column_name = 'version'
           AND data_type = 'integer'
    ) THEN
        ALTER TABLE workflows
            ALTER COLUMN version DROP DEFAULT,
            ALTER COLUMN version TYPE VARCHAR(32) USING version::VARCHAR,
            ALTER COLUMN version SET DEFAULT 'draft';
    END IF;
END $$;

-- New rows default to false; legacy rows that were all published=true stay so.
ALTER TABLE workflows ALTER COLUMN published SET DEFAULT FALSE;

-- `name` was NOT NULL before but standalone playground drafts can legitimately
-- have no name until the user hits "save as…". Relax to nullable so the draft
-- insert on app-create doesn't need a synthetic string.
ALTER TABLE workflows ALTER COLUMN name DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_workflow_app    ON workflows (app_id, version);
CREATE INDEX IF NOT EXISTS idx_workflow_tenant ON workflows (tenant_id);

COMMIT;
