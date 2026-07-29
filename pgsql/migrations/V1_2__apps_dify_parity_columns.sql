-- ============================================================================
-- Migration V1.2 · Add Dify-parity presentation + retrieval columns to apps
-- ----------------------------------------------------------------------------
-- The `apps` table (formerly `agent_definition`) was created with the minimum
-- fields needed to run an agent (name, instructions, model_id, strategy,
-- tools). Aligning with Dify's App model surfaces more usable UX:
--   description               — one-liner shown on the agent list card
--   icon / icon_background    — emoji + tint for visual identity
--   mode                      — 'agent' | 'chat' | 'workflow' | 'chatflow' | 'completion'
--   opening_statement         — auto-greeting posted on new conversation
--   suggested_questions_json  — pre-canned starter prompts (Dify parity)
--   dataset_ids_json          — knowledge bases wired in (Dify parity)
--   published                 — draft vs live gate
--
-- All new columns are nullable / defaulted so existing rows stay valid.
-- Idempotent — safe to run twice via `ADD COLUMN IF NOT EXISTS`.
-- ============================================================================

ALTER TABLE apps ADD COLUMN IF NOT EXISTS description              VARCHAR(1024);
ALTER TABLE apps ADD COLUMN IF NOT EXISTS icon                     VARCHAR(64);
ALTER TABLE apps ADD COLUMN IF NOT EXISTS icon_background          VARCHAR(32);
ALTER TABLE apps ADD COLUMN IF NOT EXISTS mode                     VARCHAR(32) DEFAULT 'agent';
ALTER TABLE apps ADD COLUMN IF NOT EXISTS opening_statement        TEXT;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS suggested_questions_json TEXT;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS dataset_ids_json         TEXT;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS published                BOOLEAN DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_apps_tenant_mode ON apps (tenant_id, mode);
CREATE INDEX IF NOT EXISTS idx_apps_published   ON apps (published);
