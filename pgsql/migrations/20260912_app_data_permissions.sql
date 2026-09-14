
-- Application data grants reference host-owned user/role/department IDs.
ALTER TABLE goodle_apps ADD COLUMN IF NOT EXISTS data_access_mode VARCHAR(20) NOT NULL DEFAULT 'ALL';
CREATE TABLE IF NOT EXISTS goodle_app_permissions (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    app_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('USER', 'ROLE', 'DEPARTMENT')),
    subject_id VARCHAR(128) NOT NULL,
    include_descendants BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_app_permission_subject UNIQUE (tenant_id, app_id, subject_type, subject_id),
    CONSTRAINT ck_app_permission_descendants CHECK (subject_type = 'DEPARTMENT' OR include_descendants = FALSE)
);
CREATE INDEX IF NOT EXISTS idx_app_permission_subject ON goodle_app_permissions (tenant_id, subject_type, subject_id);
