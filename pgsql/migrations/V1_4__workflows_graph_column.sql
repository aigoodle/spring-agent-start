-- ============================================================================
-- Migration V1.4 · workflows.graph_json → workflows.graph
-- ----------------------------------------------------------------------------
-- Historical context: three schema variants exist in the wild for the graph
-- column on the workflows table —
--
--   * spring-agent-start (legacy):   column named `graph`   of type `json`
--   * spring-agent-start early builds: column named `graph_json` of type `TEXT`
--   * databases that jumped straight to spring-agent-start: no `workflows`
--     table at all before init.sql created it (also `graph_json TEXT`).
--
-- The Java entity now standardises on {@code graph} (JsonNode field, TEXT
-- column, MyBatis-Plus JacksonTypeHandler) — matching the legacy naming and
-- Dify's own schema. This migration reconciles the three variants in place
-- so existing dev/staging DBs pick up the new writes without a manual rename.
--
-- Idempotent — safe to run repeatedly.
-- ============================================================================

BEGIN;

DO $$
BEGIN
    -- Case 1: only graph_json exists → rename it. Preserves data.
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'workflows' AND column_name = 'graph_json'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'workflows' AND column_name = 'graph'
    ) THEN
        ALTER TABLE workflows RENAME COLUMN graph_json TO graph;
    END IF;

    -- Case 2: both columns exist (bizarre but happens if someone ran the
    -- legacy DDL then spring-agent-start's init.sql on the same DB). Copy any
    -- non-null graph_json rows into graph, then drop graph_json.
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'workflows' AND column_name = 'graph_json'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'workflows' AND column_name = 'graph'
    ) THEN
        UPDATE workflows
           SET graph = graph_json::text::json  -- cast if graph is json type
         WHERE graph IS NULL AND graph_json IS NOT NULL;
        ALTER TABLE workflows DROP COLUMN graph_json;
    END IF;

    -- Case 3: neither exists → add graph (TEXT). Only reachable if some other
    -- migration/tool created the table without a graph column at all.
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'workflows' AND column_name = 'graph'
    ) THEN
        ALTER TABLE workflows ADD COLUMN graph TEXT;
    END IF;
END $$;

-- The legacy dump's graph column is `NOT NULL` — that constraint conflicts
-- with our draft-then-fill pattern (createDraft on app create inserts an empty
-- seed graph, but publishDraft snapshots may sit in a transient state before
-- graph is copied over). Drop the NOT NULL so both flows work.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'workflows' AND column_name = 'graph'
           AND is_nullable = 'NO'
    ) THEN
        ALTER TABLE workflows ALTER COLUMN graph DROP NOT NULL;
    END IF;
END $$;

COMMIT;
