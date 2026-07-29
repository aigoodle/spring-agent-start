-- ============================================================================
-- Migration V1.1 · Add tenant_id column to agent_predefined_model
-- ----------------------------------------------------------------------------
-- Problem: PredefinedModelEntity extends BaseEntity which declares tenant_id.
-- The initial `agent_predefined_model` DDL omitted this column because the
-- catalog is conceptually global, but MyBatis-Plus still injects tenant_id
-- into every generated SELECT — so any query blew up with
-- "column tenant_id does not exist".
--
-- Fix: add tenant_id with a 'system' default. Existing rows (if any) are
-- backfilled to 'system'. Future per-tenant catalog extensions can use a
-- different tenant_id.
-- ============================================================================

ALTER TABLE agent_predefined_model
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'system';

-- Backfill any pre-existing rows that were inserted before the column existed
-- (defensive: the default clause already covers new rows and re-adds).
UPDATE agent_predefined_model SET tenant_id = 'system' WHERE tenant_id IS NULL OR tenant_id = '';

CREATE INDEX IF NOT EXISTS idx_predef_tenant_provider
    ON agent_predefined_model (tenant_id, provider_name);
