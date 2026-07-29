# PostgreSQL setup

Two ways to stand up a Postgres for `spring-agent-start-server`.

## Option 1 — bare psql

```bash
createdb spring-agent
psql -d spring-agent -f pgsql/init.sql
```

Then just start the server — the default `application.yml` already targets
this database:

```bash
mvn -pl spring-agent-start-server -am spring-boot:run
```

Environment variables the app reads (all optional — defaults shown):

| Variable      | Default           |
| ------------- | ----------------- |
| `DB_HOST`     | `localhost`       |
| `DB_PORT`     | `5432`            |
| `DB_NAME`     | `spring-agent`    |
| `DB_USER`     | `postgres`        |
| `DB_PASSWORD` | `difyai123456`    |

## Option 2 — docker-compose

```bash
docker-compose -f pgsql/docker-compose.yml up -d
```

That launches PostgreSQL 16 on `localhost:5432`, creates the `spring-agent` DB,
and runs `init.sql` on first boot. Data lives in the `pgdata` named volume.

Shut it down + delete data:

```bash
docker-compose -f pgsql/docker-compose.yml down -v
```

## What the script creates

All tables live in the `public` schema. Names aligned with the
`spring-agent-start` reference project (Dify parity) — every functional-module
table now matches the naming its 1.14.2 Dify counterpart uses:

* **Model** (Dify-parity 6-table split): `agent_model_provider`,
  `agent_predefined_model`, `agent_provider_credential`, `agent_model`,
  `agent_provider_model_setting`, `agent_tenant_default_model`,
  `agent_prompt_template`
* **Knowledge**: `dataset`, `documents`, `document_segments`, `embeddings`,
  `dataset_query`
* **Workflow**: `workflows`, `workflow_runs`
* **Agent (智能体应用)**: `apps`, `messages`
* **Trigger**: `app_triggers`, `trigger_invocations`
* **Observability**: `llm_calls`

The script is idempotent (`CREATE TABLE IF NOT EXISTS` everywhere) so a re-run
against an existing DB does nothing.

## Using pgvector for embeddings

By default embeddings are stored in the same PostgreSQL via the built-in JDBC
vector store — no extension needed. To switch to native pgvector:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Then in your pom add:

```xml
<dependency>
    <groupId>io.github.aigoodle</groupId>
    <artifactId>spring-agent-start-knowledge-store-pgvector</artifactId>
</dependency>
```

And in `application.yml`:

```yaml
spring-agent:
  knowledge:
    vector-store: pgvector
```

Each dataset then gets its own scoped pgvector table under a
`{dataset_id}` naming scheme.
