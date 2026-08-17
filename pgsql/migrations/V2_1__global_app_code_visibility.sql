-- Stable internal SaaS application routing.
-- An app remains owned by tenant_id; GLOBAL only grants execution access.
ALTER TABLE goodle_apps
    ADD COLUMN IF NOT EXISTS app_code VARCHAR(100);

ALTER TABLE goodle_apps
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';

CREATE UNIQUE INDEX IF NOT EXISTS uk_apps_tenant_code
    ON goodle_apps (tenant_id, app_code);

ALTER TABLE goodle_apps
    DROP CONSTRAINT IF EXISTS ck_apps_visibility;

ALTER TABLE goodle_apps
    ADD CONSTRAINT ck_apps_visibility
    CHECK (visibility IN ('PRIVATE', 'TENANT_LIST', 'GLOBAL'));
