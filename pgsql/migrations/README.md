# pgsql/migrations

Incremental schema changes applied on top of a database already initialized
from `../init.sql`. Each file is a self-contained SQL script — apply in
order, then the DB is at the corresponding revision.

## Naming

`V<major>_<minor>__<snake_case_description>.sql`

* `V1_1__predefined_model_tenant_column.sql` — adds `tenant_id` to
  `agent_predefined_model` (Dify-parity DB-driven catalog fix).
* `V1_2__apps_dify_parity_columns.sql` — adds description / icon /
  icon_background / mode / opening_statement / suggested_questions_json /
  dataset_ids_json / published to `apps`, matching Dify's App model.
* `V1_3__workflows_app_scoped_draft.sql` — splits `apps.graph_json` out into
  a proper `workflows.app_id` draft-per-app model (Dify parity). Drops
  `apps.graph_json`, adds `apps.workflow_id`; converts `workflows.version`
  from INTEGER to VARCHAR('draft'), adds `app_id` + Dify side-cars
  (features / environment_variables / conversation_variables / output /
  marked_name / marked_comment).
* `V1_4__workflows_graph_column.sql` — aligns the graph column with the
  Java entity's {@code JsonNode graph} + MyBatis-Plus JacksonTypeHandler.
  Renames `workflows.graph_json` → `workflows.graph` (or copies + drops if
  both exist), and drops the legacy `NOT NULL` constraint that the old
  spring-agent-start dump left in place. Also updates `../init.sql` so a
  fresh install lands on the new name.

Numbering follows the same convention Flyway uses so this folder can be
wired into a real migration runner later without renaming.

## How to apply

Manual (single-shot):

```bash
psql -d spring_agent_boot -f pgsql/migrations/V1_1__predefined_model_tenant_column.sql
```

Batch (all pending, in order):

```bash
for f in pgsql/migrations/V*.sql; do
    echo "-- Applying $f"
    psql -d spring_agent_boot -f "$f" || exit 1
done
```

## Adding a new migration

1. Copy the highest-numbered file, bump the number.
2. Keep the script idempotent — `ADD COLUMN IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS`,
   `INSERT ... ON CONFLICT DO NOTHING`, etc. — so a retry after partial
   failure is safe.
3. Update `../init.sql` in the same PR so a fresh install skips the migration.
4. If the change is portable, also patch the matching module's
   `src/main/resources/db/*-schema.sql` so H2 / MySQL boots include it.
