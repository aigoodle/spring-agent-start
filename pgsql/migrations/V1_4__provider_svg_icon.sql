-- ============================================================================
-- Migration V1.4 · Add svg_icon column to agent_model_provider
-- ----------------------------------------------------------------------------
-- Motivation: the existing `icon` column is a VARCHAR(255) meant for a short
-- key or an asset URL. User-defined providers registered from the UI want to
-- ship the mark as inline SVG markup, which needs a TEXT column.
--
-- The web UI (`ProviderIcon.vue`) renders whichever wins first — the DB
-- `svg_icon`, the DB `icon` (auto-sniffed URL / inline SVG), or a matching
-- built-in file under `web/modules/agent-start/src/provider-hub/svg/`. So the
-- new column is fully additive: no backfill needed, existing rows stay
-- functional via their `icon` value or the built-in bundle.
--
-- Safety: raw SVG markup emitted by the backend is served to the browser as a
-- `data:image/svg+xml` URL through `<img>`; browsers load SVGs in "image
-- mode" which disables scripts and event handlers, so DB content cannot
-- execute XSS even without server-side sanitisation.
-- ============================================================================

ALTER TABLE agent_model_provider
    ADD COLUMN IF NOT EXISTS svg_icon TEXT;
