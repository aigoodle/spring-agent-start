-- ============================================================================
-- Migration V1.6 · apps.model_name + apps.model_provider columns
-- ----------------------------------------------------------------------------
-- Replaces the ambiguous apps.model_id column with two explicit ones:
--   model_name     — plain vendor model name, e.g. qwen3.6-plus
--   model_provider — provider key,             e.g. qwen
--
-- The console previously encoded these into a single composite string like
-- "qwen::qwen3.6-plus::LLM", but ModelService.getChatClient() only accepts an
-- agent_model.id UUID, so nothing downstream could actually resolve the value.
-- After this migration the runtime looks up the ModelEntity via
-- ModelService.findOrMaterialize(tenant, model_provider, model_name, LLM) and
-- forwards the resolved DB id to Spring AI.
--
-- Copies any existing model_id value into model_name so previously-saved apps
-- keep working. The legacy column is dropped once the copy succeeds.
-- ============================================================================

BEGIN;

ALTER TABLE apps ADD COLUMN IF NOT EXISTS model_name     VARCHAR(128);
ALTER TABLE apps ADD COLUMN IF NOT EXISTS model_provider VARCHAR(64);

-- Best-effort backfill: legacy rows stored either a plain model name or the
-- composite provider::model::type. Copy the third-to-last segment as the model
-- name and the first as the provider so both fields land populated when we can
-- confidently split.
UPDATE apps
   SET model_name     = COALESCE(model_name, split_part(model_id, '::', 2)),
       model_provider = COALESCE(model_provider, split_part(model_id, '::', 1))
 WHERE model_id IS NOT NULL
   AND position('::' IN model_id) > 0;

UPDATE apps
   SET model_name = model_id
 WHERE model_id IS NOT NULL
   AND position('::' IN model_id) = 0
   AND model_name IS NULL;

ALTER TABLE apps DROP COLUMN IF EXISTS model_id;

COMMIT;
