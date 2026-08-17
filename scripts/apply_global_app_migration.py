"""Apply the idempotent SaaS global-app migration to PostgreSQL."""

from __future__ import annotations

import os
import secrets
import time

import psycopg


def required(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"missing environment variable: {name}")
    return value


def main() -> None:
    connection = psycopg.connect(
        host=required("AGENT_DB_HOST"),
        port=int(os.environ.get("AGENT_DB_PORT", "5432")),
        dbname=required("AGENT_DB_NAME"),
        user=required("AGENT_DB_USER"),
        password=required("AGENT_DB_PASSWORD"),
        connect_timeout=10,
    )
    try:
        with connection.transaction(), connection.cursor() as cursor:
            cursor.execute(
                """
                ALTER TABLE goodle_apps
                    ADD COLUMN IF NOT EXISTS app_code VARCHAR(100);
                ALTER TABLE goodle_apps
                    ADD COLUMN IF NOT EXISTS visibility VARCHAR(20)
                    NOT NULL DEFAULT 'PRIVATE';
                CREATE UNIQUE INDEX IF NOT EXISTS uk_apps_tenant_code
                    ON goodle_apps (tenant_id, app_code);
                ALTER TABLE goodle_apps
                    DROP CONSTRAINT IF EXISTS ck_apps_visibility;
                ALTER TABLE goodle_apps
                    ADD CONSTRAINT ck_apps_visibility
                    CHECK (visibility IN ('PRIVATE', 'TENANT_LIST', 'GLOBAL'));
                """
            )
            target_app_id = os.environ.get("AGENT_GLOBAL_APP_ID")
            target_app_code = os.environ.get("AGENT_GLOBAL_APP_CODE")
            if target_app_id and target_app_code:
                cursor.execute(
                    """
                    UPDATE goodle_apps
                    SET app_code = %s, visibility = 'GLOBAL', updated_at = CURRENT_TIMESTAMP
                    WHERE id = %s
                    """,
                    (target_app_code.strip().lower(), target_app_id),
                )
                if cursor.rowcount != 1:
                    raise RuntimeError(f"target app not found: {target_app_id}")

            web_app_code = os.environ.get("AGENT_WEB_APP_CODE")
            if web_app_code:
                cursor.execute(
                    """
                    SELECT id, tenant_id
                    FROM b_sys_config
                    WHERE code = 'web_config'
                    ORDER BY CASE WHEN tenant_id = %s THEN 0 ELSE 1 END
                    LIMIT 1
                    """,
                    (os.environ.get("AGENT_ROOT_TENANT_ID", "1449618464510685185"),),
                )
                config = cursor.fetchone()
                if config is None:
                    raise RuntimeError("web_config not found")
                cursor.execute(
                    """
                    SELECT id FROM b_sys_config_item
                    WHERE main_id = %s AND code = 'AGENT_APP_CODE'
                    LIMIT 1
                    """,
                    (config[0],),
                )
                existing = cursor.fetchone()
                if existing:
                    cursor.execute(
                        """
                        UPDATE b_sys_config_item
                        SET content = %s, status = '1', updated_time = CURRENT_TIMESTAMP
                        WHERE id = %s
                        """,
                        (web_app_code, existing[0]),
                    )
                else:
                    cursor.execute(
                        """
                        INSERT INTO b_sys_config_item
                            (id, main_id, code, name, type, content, description,
                             status, tenant_id, created_time, updated_time, delete_flag)
                        VALUES
                            (%s, %s, 'AGENT_APP_CODE', '智能体编码', 'text', %s,
                             NULL, '1', %s,
                             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '0')
                        """,
                        (str(int(time.time() * 1000) * 1_000_000
                             + secrets.randbelow(1_000_000)),
                         config[0], web_app_code, config[1]),
                    )

        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT column_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'goodle_apps'
                  AND column_name IN ('tenant_id', 'app_code', 'visibility')
                ORDER BY ordinal_position
                """
            )
            for row in cursor.fetchall():
                print("column", *row, sep=" | ")

            cursor.execute(
                """
                SELECT id, tenant_id, name, app_code, visibility, published
                FROM goodle_apps
                WHERE tenant_id = %s
                ORDER BY updated_at DESC NULLS LAST
                LIMIT 20
                """,
                (os.environ.get("AGENT_ROOT_TENANT_ID", "1449618464510685185"),),
            )
            rows = cursor.fetchall()
            print(f"root_apps | {len(rows)}")
            for row in rows:
                print("app", *row, sep=" | ")

            cursor.execute(
                """
                SELECT tenant_id, COUNT(*)
                FROM goodle_apps
                GROUP BY tenant_id
                ORDER BY COUNT(*) DESC, tenant_id
                """
            )
            for row in cursor.fetchall():
                print("tenant_apps", *row, sep=" | ")

            cursor.execute(
                """
                SELECT id, tenant_id, name, app_code, visibility, published
                FROM goodle_apps
                ORDER BY updated_at DESC NULLS LAST
                LIMIT 20
                """
            )
            for row in cursor.fetchall():
                print("recent_app", *row, sep=" | ")

            cursor.execute(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name ILIKE '%tenant%'
                ORDER BY table_name
                """
            )
            for row in cursor.fetchall():
                print("tenant_table", row[0], sep=" | ")

            cursor.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'a_tenant'
                ORDER BY ordinal_position
                """
            )
            print("a_tenant_columns | " + ",".join(row[0] for row in cursor.fetchall()))
            cursor.execute("SELECT * FROM a_tenant ORDER BY 1 LIMIT 20")
            for row in cursor.fetchall():
                print("tenant", *row, sep=" | ")

            cursor.execute(
                """
                SELECT c.id, c.tenant_id, c.code, i.id, i.code, i.name, i.content
                FROM b_sys_config c
                LEFT JOIN b_sys_config_item i ON i.main_id = c.id
                WHERE c.code = 'web_config'
                  AND (i.code IS NULL OR i.code IN ('AGENT_API_KEY', 'AGENT_APP_CODE'))
                ORDER BY c.tenant_id, i.code
                """
            )
            for row in cursor.fetchall():
                print("web_config", *row, sep=" | ")

            cursor.execute(
                """
                SELECT column_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'b_sys_config_item'
                ORDER BY ordinal_position
                """
            )
            for row in cursor.fetchall():
                print("config_item_column", *row, sep=" | ")
    finally:
        connection.close()


if __name__ == "__main__":
    main()
