# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## What this is

`spring-agent-start` is a set of **embeddable Spring Boot starter libraries** (not an app) that
repackage Dify/n8n/RAGFlow ideas — model management, knowledge/RAG, tools, an agent runtime, and a
workflow DAG engine — as independently-importable modules. Stack: Spring Boot 3.5.6, Spring AI 1.1.2,
MyBatis-Plus 3.5.10, Lombok, Java 21.

## Build & test

```bash
mvn clean install                      # build + test all modules
mvn -pl spring-agent-start-model -am test    # one module (+ its upstream deps via -am)
mvn -pl spring-agent-start-model -am -Dtest=ProviderRegistryTest test          # single test class
mvn -pl spring-agent-start-agent  -am -Dtest=AgentRuntimeTest#methodName test   # single test method
```

- Unit/integration tests run against an **in-memory H2** DB auto-created from each module's
  `src/main/resources/db/*-schema.sql`. No external DB needed for the test suite.
- Tests needing a live LLM run against a local **Ollama** server and **skip automatically** when it is
  unreachable (env: `OLLAMA_BASE_URL`, `OLLAMA_TEST_MODEL`). Don't treat these skips as failures.
- Cross-module integration tests live in `spring-agent-start-example`, not the individual modules.

### Running the demo app

`spring-agent-start-example` is the runnable Spring Boot app (`SpringAgentExampleApplication`, see
`DemoController` for endpoints). It targets **PostgreSQL** (not H2) and listens on port `18090`.

```bash
mvn -pl spring-agent-start-example -am spring-boot:run    # needs a Postgres at localhost:5432 (see application.yml)
```

## Module layout & dependency order

Build order is dependency-driven; respect it when adding cross-module references:

```
common → model → { knowledge, tools } → agent → workflow → example
```

| Module | Role |
|--------|------|
| `spring-agent-start-common` | JSON utils, AES-GCM `TextEncryptor`, base entity, `AgentException` |
| `spring-agent-start-model` | `ModelProvider` SPI, encrypted credentials, `ModelInstanceFactory`, `ModelService` (returns Spring AI `ChatClient`/`EmbeddingModel`). Ships built-in OpenAI-compatible presets (openai, deepseek, zhipu, moonshot, qwen, volcengine, siliconflow) + Ollama out of the box. |
| `spring-agent-start-model-provider/` | Aggregator directory for optional native-SDK `ModelProvider` starters. Each child upgrades the matching built-in OpenAI-compat preset. |
| ` └── spring-agent-start-model-provider-zhipu` | Replaces the built-in `"zhipu"` preset with the official spring-ai-zhipuai SDK (GLM-4V vision, native chat/embedding). |
| ` └── spring-agent-start-model-provider-deepseek` | Replaces the built-in `"deepseek"` preset with the official spring-ai-deepseek SDK (reasoner mode). Chat only. |
| ` └── spring-agent-start-model-provider-volcengine` | Volcengine Ark / Doubao. Same OpenAI-compat transport but exposes an explicit `endpointId` credential (ep-xxx) and ships Doubao presets. |
| `spring-agent-start-knowledge` | Datasets → documents → chunks, template chunking, hybrid (vector+keyword) retrieval, pluggable `DocumentReader`/`Reranker` SPIs (built-ins: text/markdown/html/tika readers, noop/weighted/model rerankers) |
| `spring-agent-start-knowledge-store/` | Aggregator directory for optional `VectorStoreFactory` starters. Add one child to switch away from the in-memory default. |
| ` └── spring-agent-start-knowledge-store-pgvector` | `VectorStoreFactory` backed by pgvector (one table per dataset). |
| ` └── spring-agent-start-knowledge-store-elasticsearch` | `VectorStoreFactory` backed by Elasticsearch (one index per dataset). |
| ` └── spring-agent-start-knowledge-store-milvus` | `VectorStoreFactory` backed by Milvus (one collection per dataset). |
| `spring-agent-start-tools` | `Tool` SPI, `ToolRegistry`, `ToolProvider` plug-point, Spring AI `ToolCallback` adapter |
| `spring-agent-start-agent` | `AgentStrategy` runtime, JDBC memory, multi-agent delegation, human-in-the-loop approval |
| `spring-agent-start-workflow` | DAG `WorkflowEngine`, `NodeExecutor` nodes, workflow persistence |
| `spring-agent-start-example` | Runnable demo + cross-module integration tests |

Each functional module ships a `@AutoConfiguration` class registered via
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — adding the jar is
the only wiring needed.

## Architecture: the extension model

The whole system is built around **bean-discovered SPIs** — to extend any layer, publish a Spring bean
implementing the interface; auto-config picks it up (and `@ConditionalOnMissingBean` lets you override
defaults). The four SPIs:

- **`ModelProvider`** (model) — stateless factory building Spring AI `ChatModel`/`EmbeddingModel` from a
  resolved `ModelEndpoint`. `ModelProviderRegistry` merges app-published provider beans (precedence)
  with built-ins (OpenAI-compatible presets + Ollama; toggle via `registerBuiltinProviders`). The
  precedence rule is `putIfAbsent`: the first entry to claim a name wins, so a starter jar shipping
  a `"zhipu"` bean transparently upgrades the built-in `"zhipu"` OpenAI-compat preset. The
  `spring-agent-start-model-provider-*` starters use this to swap in native official SDKs. Caching of built
  instances belongs to `ModelInstanceFactory`, **not** the provider.
- **`NodeExecutor`** (workflow) — one bean per `NodeType` (the n8n "node" model). Built-ins:
  START/END/ANSWER/TEMPLATE_TRANSFORM/VARIABLE_ASSIGNER/VARIABLE_AGGREGATOR/IF_ELSE/HTTP_REQUEST/
  LLM/AGENT/QUESTION_CLASSIFIER/PARAMETER_EXTRACTOR/LIST_OPERATOR/ITERATION, plus KNOWLEDGE_RETRIEVAL,
  DOCUMENT_EXTRACTOR and TOOL nodes wired **only when the knowledge/tools modules are on the
  classpath** (`@ConditionalOnClass`/`@ConditionalOnBean`), and CODE wired only when
  `commons-jexl3` is on the classpath. `ITERATION` runs a nested sub-graph once per element of
  a list variable — it gets the engine via `ObjectProvider<WorkflowEngine>` to break the DI cycle.
- **`AgentStrategy`** (agent) — reasoning loop selected by `AgentStrategyType`. Built-ins: `ReActStrategy`,
  `FunctionCallingStrategy`.
- **`Tool` / `ToolProvider`** (tools) — individual tools and bulk providers (MCP/OpenAPI/plugin sources).

### Workflow engine model

`WorkflowEngine.run(graph, inputs, conversationId)` walks the graph from the START node via a work
queue. Each node's outputs go into a **variable pool** keyed by node id; other nodes reference them with
`{{#node.field#}}` templates (and `{{#sys.field#}}` for run inputs). Branching nodes (e.g. IF_ELSE)
choose an outgoing **edge handle** and only matching edges are followed. A `MAX_STEPS = 1000` ceiling
guards against accidental cycles — real loops are an ITERATION node, not re-entrant edges. Per-step
`StepRecord`s are recorded for timing/observability.

## Persistence & config conventions

- Every module ships portable DDL at `src/main/resources/db/<module>-schema.sql` (H2 + MySQL; the
  example also runs them on Postgres). When you add an entity/table, update the matching schema file.
- Mappers are MyBatis-Plus; each auto-config does `@MapperScan` on its own `...mapper` package.
- **Multi-tenancy is opt-in**: a blank `tenant_id` defaults to `"default"`.
- Model credentials are **AES-GCM encrypted at rest**; set the key in production via
  `spring-agent.model.encryption-secret`.
- Knowledge vector store defaults to in-memory `SimpleVectorStore`. Switch by setting
  `spring-agent.knowledge.vector-store` to one of `jdbc | pgvector | elasticsearch | milvus` and
  adding the matching optional starter (`spring-agent-start-knowledge-store-*`) to your pom. `jdbc` needs no
  extra jar (it uses the in-project `JdbcVectorStore`); the others each ship a `VectorStoreFactory`
  auto-configured behind `@ConditionalOnProperty`. You can still publish your own `VectorStoreFactory`
  bean to override any of these.
- Approval gate defaults to `AutoApproveGate` (override the `ApprovalGate` bean for real human-in-the-loop).

## Conventions

- Base package is `io.github.aigoodle.<module>`; Maven groupId is `io.github.aigoodle`.
- Auto-config beans are guarded with `@ConditionalOnMissingBean` so applications can replace any
  default — preserve this when adding beans.
