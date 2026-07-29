# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`spring-agent-start` is a set of **embeddable Spring Boot starter libraries** that repackage
Dify/n8n/RAGFlow ideas — model management, knowledge/RAG, tools, an agent runtime, a workflow DAG
engine, triggers, and LLMOps metering — as independently-importable modules, plus two REST layers
(MVC admin CRUD and a reactive OpenAI/Dify-compatible chat surface). Stack: Spring Boot 3.5.6,
Spring AI 1.1.2, MyBatis-Plus 3.5.10, Lombok, Java 21.

`AGENTS.md` is a sibling mirror of this file for Codex — keep the two in sync when editing either.

## Build & test

```bash
mvn clean install                      # build + test all modules
mvn -pl agent-start-model -am test    # one module (+ its upstream deps via -am)
mvn -pl agent-start-model -am -Dtest=ProviderRegistryTest test          # single test class
mvn -pl agent-start-agent  -am -Dtest=AgentRuntimeTest#methodName test   # single test method
```

- Unit/integration tests run against an **in-memory H2** DB auto-created from each module's
  `src/main/resources/db/*-schema.sql`. No external DB needed for the test suite.
- Tests needing a live LLM run against a local **Ollama** server and **skip automatically** when it is
  unreachable (env: `OLLAMA_BASE_URL`, `OLLAMA_TEST_MODEL`). Don't treat these skips as failures.
- Cross-module integration tests live in `agent-start-example`, not the individual modules.

## Runnable apps (there are two)

| App | Stack | Database | Run |
|-----|-------|----------|-----|
| `agent-start-example` | servlet/Tomcat, port 18090 | **H2 by default** (`MODE=PostgreSQL`); real Postgres via `--spring.profiles.active=postgres` | `mvn -pl agent-start-example -am spring-boot:run` |
| `agent-start-server` | **reactive/Netty** (`spring.main.web-application-type=reactive`), port 18090 | **PostgreSQL only** — no H2 fallback (schema uses PG-native types); provision via `pgsql/docker-compose.yml` or `pgsql/init.sql` | `mvn -pl agent-start-server -am spring-boot:run` |

- `example` is the demo: it imports the MVC `web` module plus `DemoController` /
  `AgentDemoController` / `AutomationDemoController` / `ObservabilityDemoController` showcase
  endpoints, and its `AgentTriggerDispatcher` (~20 lines) demonstrates the `TriggerDispatcher` SPI.
- `server` is the standalone production-shaped backend. It pulls `agent-start-web` **with
  `spring-boot-starter-web` excluded** — generic `@RestController` methods still route under the
  reactive dispatcher, but servlet-only types (`MultipartFile`, `SseEmitter`) fail at runtime there;
  streaming chat comes from `agent-start-completion` instead. When adding endpoints used by
  both apps, keep them servlet-agnostic or provide a reactive counterpart in `completion`.
- `docker/docker-compose.yml` brings up the full demo (Postgres + backend + frontend); the root
  `Dockerfile` builds the backend standalone.

## Module layout & dependency order

Build order is dependency-driven; respect it when adding cross-module references:

```
common → model → { knowledge, tools } → agent → workflow → { trigger, observability, web } → completion → { web-spring-starter, completion-spring-starter, server, example }
```

| Module | Role |
|--------|------|
| `agent-start-common` | JSON utils, AES-GCM `TextEncryptor`, base entity, `AgentException` |
| `agent-start-model` | `ModelProvider` SPI, `ChatModelDecorator` SPI, encrypted credentials, `ModelInstanceFactory`, `ModelService` (returns Spring AI `ChatClient`/`EmbeddingModel`). Built-in OpenAI-compatible presets (openai, deepseek, zhipu, moonshot, qwen, volcengine, siliconflow) + Ollama. |
| `agent-start-provider/` | Aggregator for optional native-SDK `ModelProvider` starters; each child upgrades the matching built-in OpenAI-compat preset. |
| ` └── …-zhipu` | Official spring-ai-zhipuai SDK (GLM-4V vision, native chat/embedding). |
| ` └── …-deepseek` | Official spring-ai-deepseek SDK (reasoner mode). Chat only. |
| ` └── …-volcengine` | Volcengine Ark / Doubao; adds an explicit `endpointId` (ep-xxx) credential. |
| `agent-start-knowledge` | Datasets → documents → chunks, template chunking, hybrid (vector+keyword) retrieval, `DocumentReader`/`Chunker`/`Reranker` SPIs, optional RabbitMQ async ingestion |
| `agent-start-store/` | Aggregator for optional `VectorStoreFactory` starters (`-pgvector`, `-elasticsearch`, `-milvus`). |
| `agent-start-tools` | `Tool` SPI, `ToolRegistry`, `ToolProvider` plug-point, Spring AI `ToolCallback` adapter, **MCP client** (`McpToolProvider`) |
| `agent-start-agent` | `AgentStrategy` runtime, JDBC + semantic vector memory, multi-agent delegation, human-in-the-loop approval |
| `agent-start-workflow` | DAG `WorkflowEngine`, `NodeExecutor` nodes, workflow persistence |
| `agent-start-trigger` | Webhook / cron / event triggers → async dispatch via `TriggerDispatcher` SPI (built-in: `WorkflowTriggerDispatcher`), invocation history + replay |
| `agent-start-observability` | LLMOps: `MeteringChatModelDecorator` (a `ChatModelDecorator`) records per-call token/cost/latency into `LlmCallRecord`; `LlmMetricsService` aggregates per model |
| `agent-start-web` | **MVC** REST admin layer: Dify-style controllers (models, datasets, workflows, agents, triggers, conversations, prompt templates, tags, tools, llmops…), `ApiResponse`/`PageResult` envelopes, `SseEmitter` streaming, springdoc webmvc |
| `agent-start-completion` | **WebFlux** reactive chat surface: OpenAI-compatible `/chat/completions` + Dify `/chat-messages` (streaming and blocking) via `AgentChatGenerator`/`WorkflowChatGenerator` |
| `agent-start-web-spring-starter`, `agent-start-completion-spring-starter` | Pom-only aggregators: MVC/Tomcat stack vs WebFlux/Netty stack. No code. |
| `agent-start-server` | Standalone reactive server bundling every module (see above) |
| `agent-start-example` | Runnable H2-first demo + cross-module integration tests |

Each functional module ships a `@AutoConfiguration` registered via
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — adding the jar is
the only wiring needed.

## Architecture: the extension model

The whole system is built around **bean-discovered SPIs** — publish a Spring bean implementing the
interface and auto-config picks it up (`@ConditionalOnMissingBean` lets you override defaults).
The SPIs:

- **`ModelProvider`** (model) — stateless factory building Spring AI `ChatModel`/`EmbeddingModel`
  from a resolved `ModelEndpoint`. `ModelProviderRegistry` merges app-published provider beans with
  built-ins (toggle built-ins via `spring-agent.model.register-builtin-providers`). Precedence is
  `putIfAbsent`: the first entry to claim a name wins, so a starter shipping a `"zhipu"` bean
  transparently upgrades the built-in OpenAI-compat preset. Instance caching belongs to
  `ModelInstanceFactory`, **not** the provider.
- **`ChatModelDecorator`** (model) — wraps every built `ChatModel`; observability's metering is
  implemented purely as a decorator bean. Add cross-cutting LLM behavior this way.
- **`NodeExecutor`** (workflow) — one bean per `NodeType` (the n8n "node" model). Canonical types:
  START/END/ANSWER/IF_ELSE/ITERATION, TEMPLATE_TRANSFORM/VARIABLE_ASSIGNER/VARIABLE_AGGREGATOR/
  LIST_OPERATOR/CODE (JEXL, only when `commons-jexl3` is on the classpath), HTTP_REQUEST/
  SERVICE_API/DOCUMENT_EXTRACTOR, LLM/AGENT/QUESTION_CLASSIFIER/PARAMETER_EXTRACTOR, plus
  KNOWLEDGE_RETRIEVAL and TOOL wired **only when the knowledge/tools modules are on the classpath**
  (`@ConditionalOnClass`/`@ConditionalOnBean`). `NodeType` also maps Dify-parity aliases
  (e.g. `"condition"` → `IF_ELSE`). `ITERATION` runs a nested sub-graph per list element — it gets
  the engine via `ObjectProvider<WorkflowEngine>` to break the DI cycle.
- **`AgentStrategy`** (agent) — reasoning loop selected by `AgentStrategyType`. Built-ins:
  `ReActStrategy`, `FunctionCallingStrategy`, `PlanExecuteStrategy`.
- **`Tool` / `ToolProvider`** (tools) — individual tools and bulk providers. MCP servers configured
  under `spring-agent.tools.mcp.servers[*]` (stdio or HTTP) join the registry via `McpToolProvider`.
- **`VectorStoreFactory` / `DocumentReader` / `Chunker` / `Reranker`** (knowledge) — swap the vector
  DB, file-format parsing, chunking template, or post-retrieval scoring without touching the pipeline.
- **`TriggerDispatcher`** (trigger) — bind a trigger to something other than a workflow (the example's
  `AgentTriggerDispatcher` drives an agent from a webhook).

### Workflow engine model

`WorkflowEngine.run(graph, inputs, conversationId)` walks the graph from START via a work queue.
Each node's outputs go into a **variable pool** keyed by node id; other nodes reference them with
`{{#node.field#}}` templates (`{{#sys.field#}}` for run inputs). Branching nodes choose an outgoing
**edge handle**; only matching edges are followed. A `MAX_STEPS = 1000` ceiling guards against
accidental cycles — real loops are an ITERATION node, not re-entrant edges. Per-step `StepRecord`s
are recorded for timing/observability.

## The web layer split (MVC vs WebFlux)

- `web` controllers are all prefixed with **`/agent-start`** (`CONTROLLER_PATH_PREFIX` in
  `SpringAgentWebAutoConfiguration`). `spring-agent.web.base-path` defaults to empty and should
  **stay empty** — setting it to e.g. `/api/v1` produces `/agent-start/api/v1/…` and breaks the
  frontend proxy.
- `completion` endpoints (`/chat-messages`, `/chat/completions/{appId}`, `/conversations`,
  `/messages`) are reactive and live outside the `/agent-start` prefix.
- `web` uses springdoc **webmvc** flavor; reactive hosts (server) swap in **webflux** flavor — both
  are managed in the root pom at the Boot-3.5-compatible 2.8.x line (2.6.x breaks).
- The example sets `accept-case-insensitive-enums: true` and `fail-on-unknown-properties: false`
  because the visual workflow designer posts lowercase node types (`"start"`) and UI-only fields
  (`position`, `viewport`); preserve this tolerance when touching workflow DTOs.

## Persistence & config conventions

- Every module ships portable DDL at `src/main/resources/db/<module>-schema.sql` (H2 + MySQL/Postgres;
  current files: model, knowledge, workflow, agent, trigger, observability). Both runnable apps load
  all of them via `spring.sql.init` with `continue-on-error: true`. When you add an entity/table,
  update the matching schema file.
- Mappers are MyBatis-Plus; each auto-config does `@MapperScan` on its own `...mapper` package.
- **Multi-tenancy is opt-in**: a blank `tenant_id` defaults to `"default"`.
- Model credentials are **AES-GCM encrypted at rest**; set the key via
  `spring-agent.model.encryption-secret` (defaults to a demo secret).
- Knowledge vector store defaults to in-memory `SimpleVectorStore`; both runnable apps set it to
  `jdbc` (in-project `JdbcVectorStore`, same DB). Switch via `spring-agent.knowledge.vector-store`
  (`jdbc | pgvector | elasticsearch | milvus`) plus the matching optional starter, or publish your
  own `VectorStoreFactory` bean.
- Knowledge ingestion can go async: `spring-agent.knowledge.async.enabled=true` with
  `starter-amqp` on the classpath uses RabbitMQ (`kb.document.ingest` queue + DLQ); without a broker
  it falls back to an in-process worker pool.
- Agent memory: `spring-agent.agent.memory=jdbc` (default) or `vector` (semantic memory backed by
  the knowledge module).
- Approval gate defaults to `AutoApproveGate` (override the `ApprovalGate` bean for real HITL).

## Conventions

- Base package is `io.github.aigoodle.<module>`; Maven groupId is `io.github.aigoodle`.
- Auto-config beans are guarded with `@ConditionalOnMissingBean` so applications can replace any
  default — preserve this when adding beans. Cross-module optional wiring uses
  `@ConditionalOnClass`/`@ConditionalOnBean` (see the KNOWLEDGE_RETRIEVAL/TOOL node executors).
- The `org/` directory at repo root contains a patched copy of Spring's `DefaultRestClient` — it is
  **not part of any Maven module** and is never compiled; don't edit it expecting build effects.
- `pgsql/` holds Postgres provisioning for the `server` app (`init.sql`, `docker-compose.yml`,
  `migrations/`) plus reference Dify SQL dumps.
