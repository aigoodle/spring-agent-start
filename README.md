# spring-agent-start

**Modular, Dify/n8n-inspired AI building blocks for Spring Boot.** Import only the
module you need — model management, a knowledge base (RAG), or workflow/agent
orchestration — and wire it into your own application. The front end is intentionally
left to you; these are libraries, not a monolith.

Built on **Spring Boot 3.5 + Spring AI 1.1 + MyBatis-Plus + Java 21**.

---

## Why another one?

Dify and n8n are great products but they are full applications. `spring-agent-start`
takes their best ideas — Dify's model providers / RAG pipeline / visual workflow, n8n's
node-and-connector extensibility, RAGFlow's template chunking and hybrid retrieval — and
repackages them as **embeddable, independently-usable Spring Boot starters** for the JVM
ecosystem, so you can drop enterprise-grade agent capabilities into an existing Java system.

## Modules

| Module | Coordinate | Depends on | What it gives you |
|--------|------------|-----------|-------------------|
| `agent-start-common` | `io.github.aigoodle:agent-start-common` | – | JSON, AES-GCM crypto, base entity |
| `agent-start-model` | `io.github.aigoodle:agent-start-model` | common | Model providers, encrypted credentials, model instance factory, chat/embedding runtime |
| `agent-start-knowledge` | `io.github.aigoodle:agent-start-knowledge` | model | Datasets, document ingestion, template chunking, vector + keyword hybrid retrieval |
| `agent-start-tools` | `io.github.aigoodle:agent-start-tools` | model | Tool/connector SPI, built-in tools (calculator, time, HTTP), Spring AI `ToolCallback` adapter, **MCP client** (stdio + HTTP) |
| `agent-start-agent` | `io.github.aigoodle:agent-start-agent` | model, tools, *(knowledge optional)* | Agent runtime: strategies (ReAct, function-calling, plan-execute), JDBC + semantic vector memory, multi-agent delegation, human-in-the-loop approval |
| `agent-start-trigger` | `io.github.aigoodle:agent-start-trigger` | workflow | Triggers/automation: webhook, cron and event triggers driving workflows async, with invocation history + replay |
| `agent-start-observability` | `io.github.aigoodle:agent-start-observability` | model | LLMOps: per-call token + cost + latency metering for every LLM call, persisted, with aggregation by model |
| `agent-start-workflow` | `io.github.aigoodle:agent-start-workflow` | model, *(knowledge + tools optional)* | DAG engine, nodes (LLM, agent, tool, condition, HTTP, template, classifier, knowledge), workflow persistence |

```
        agent-start-common
                 │
        agent-start-model ───────────────┐
            │                              │
   agent-start-knowledge ──(optional)──► agent-start-workflow
```

Each functional module ships a Spring Boot auto-configuration, so adding the jar is all
the wiring you need. Want only the knowledge base? Import `agent-start-knowledge`
(it pulls `agent-start-model`). Want only orchestration? Import `agent-start-workflow`.

---

## 1. Model management (`agent-start-model`)

A provider abstraction over Spring AI. Built-in OpenAI-compatible presets (OpenAI,
DeepSeek, Zhipu, Moonshot, Qwen, Volcengine, SiliconFlow) plus Ollama; add your own by
publishing a `ModelProvider` bean. Credentials are AES-GCM encrypted at rest.

```java
// Register a model (credentials are encrypted automatically)
ModelEntity gpt = modelService.register(ModelRegistration.builder()
        .tenantId("acme").providerName("openai").modelName("gpt-4o-mini")
        .modelType(ModelType.LLM)
        .credentials(Map.of("apiKey", "sk-..."))
        .asDefault(true)
        .build());

// Use it — you get a ready Spring AI ChatClient
String answer = modelService.getChatClient(gpt.getId())
        .prompt().user("Hello!").call().content();

// Embeddings work the same way
EmbeddingModel embed = modelService.getEmbeddingModel(embeddingModelId);
```

## 2. Knowledge base / RAG (`agent-start-knowledge`)

Dataset → document → chunk pipeline with RAGFlow-inspired **template chunking**
(`NAIVE`, `PARENT_CHILD`, `QA`, `MARKDOWN`, `ONE`) and **hybrid retrieval** that fuses
dense vector similarity with sparse keyword overlap. Default vector store is in-memory
(`SimpleVectorStore`); plug in pgvector / Elasticsearch / Milvus via a `VectorStoreFactory`
bean or the matching `agent-start-store-*` starter.

Two extension SPIs let you swap the heavy lifting without touching the pipeline:

- **`DocumentReader`** — how a raw payload becomes text. Built-ins: `text`, `markdown`,
  `html`, `tika` (fallback for PDF/DOCX/PPTX/XLSX/RTF and everything else Tika supports).
  Publish a bean to add a proprietary format.
- **`Reranker`** — how the fused candidate list gets re-scored. Built-ins:
  `noop` (identity), `weighted` (rescores by vector + keyword + length prior),
  `model` (asks any registered LLM to score each candidate). Turn it on with
  `rerankEnabled` on the dataset's `RetrievalConfig` and pick a `rerankerName`.

```java
DatasetEntity ds = datasetService.create(CreateDatasetRequest.builder()
        .tenantId("acme").name("handbook")
        .embeddingModelId(embeddingModelId)
        .indexingTechnique(IndexingTechnique.HIGH_QUALITY)
        .build());

knowledgeService.addText(ds.getId(), "policy.md", "... long document ...");
knowledgeService.addFile(ds.getId(), "manual.pdf", pdfBytes);   // PDF/DOCX/PPTX via Tika
knowledgeService.addFile(ds.getId(), "notes.html", htmlBytes);  // HtmlDocumentReader

List<RetrievedSegment> hits = knowledgeService.retrieve(ds.getId(),
        RetrievalRequest.builder().query("how do I request leave?")
                .method(RetrievalMethod.HYBRID).topK(5).build());
```

## 3. Workflow & agents (`agent-start-workflow`)

A DAG engine with a variable pool and `{{#node.field#}}` references. Built-in node types:

| Category | Nodes |
|----------|-------|
| Flow control | `START`, `END`, `ANSWER`, `IF_ELSE`, `ITERATION` |
| Data | `TEMPLATE_TRANSFORM`, `VARIABLE_ASSIGNER`, `VARIABLE_AGGREGATOR`, `LIST_OPERATOR`, `CODE` (JEXL) |
| I/O | `HTTP_REQUEST`, `DOCUMENT_EXTRACTOR` *(needs knowledge)*, `KNOWLEDGE_RETRIEVAL` *(needs knowledge)*, `TOOL` *(needs tools)* |
| LLM | `LLM`, `AGENT`, `QUESTION_CLASSIFIER`, `PARAMETER_EXTRACTOR` |

Add your own node by publishing a `NodeExecutor` bean (the n8n connector model).
`ITERATION` runs a nested sub-graph once per element of a list variable; `CODE` runs a
JEXL script against the pool (pull `commons-jexl3` in to enable it).

```java
WorkflowGraph g = new WorkflowGraph();
g.addNode(NodeDef.of("start", NodeType.START));
g.addNode(NodeDef.of("kb", NodeType.KNOWLEDGE_RETRIEVAL)
        .with("datasetIds", List.of(datasetId)).with("query", "{{#sys.query#}}").with("topK", 3));
g.addNode(NodeDef.of("llm", NodeType.LLM).with("modelId", modelId)
        .with("systemPrompt", "Answer using the context.")
        .with("userPrompt", "Context:\n{{#kb.result#}}\n\nQuestion: {{#sys.query#}}"));
g.addNode(NodeDef.of("end", NodeType.END).with("outputs", Map.of("answer", "{{#llm.text#}}")));
g.addEdge(EdgeDef.of("start", "kb"));
g.addEdge(EdgeDef.of("kb", "llm"));
g.addEdge(EdgeDef.of("llm", "end"));

WorkflowRunResult r = workflowService.runGraph(g, Map.of("query", "..."), null);
String answer = String.valueOf(r.output("answer"));   // a full RAG-over-workflow answer
```

---

## Runnable demo (`agent-start-example`)

A single Spring Boot app wiring **every** module on one database (PostgreSQL by default;
set `spring-agent.knowledge.vector-store=jdbc` to keep embeddings in the same DB, no
pgvector needed). Endpoints:

| Endpoint | Module(s) | What it shows |
|----------|-----------|---------------|
| `POST /models` | model | Register an Ollama / OpenAI-compatible model (credentials encrypted) |
| `POST /datasets` · `POST /ask` | knowledge + workflow | Ingest text, then answer via a RAG workflow |
| `GET /tools` · `POST /agents` · `POST /agents/{id}/chat` | tools + agent | List tools; create a ReAct agent; chat (it calls tools) |
| `POST /automation/webhook` · `POST /triggers/webhook/{path}` | trigger | Create a webhook trigger (→ workflow or agent) and fire it async |
| `GET /automation/triggers/{id}/invocations` · `POST /automation/invocations/{id}/replay` | trigger | Invocation history + replay |
| `GET /llmops/stats` · `GET /llmops/total` | observability | Token / cost / latency aggregated per model |

`AgentTriggerDispatcher` in the example is a ~20-line class showing how to extend the
trigger `TriggerDispatcher` SPI so a webhook can drive an **agent** (not just a workflow).

A verified run (Ollama `llama3.2` + `nomic-embed-text`, PostgreSQL): register models →
ingest → `POST /ask` answers from the document → create a calculator agent that computes
`23×19 = 437` → a webhook fires the agent to compute `5+5 = 10` asynchronously (token-secured,
recorded for replay) → `GET /llmops/stats` reports the 5 LLM calls and their token usage.

## Quick start (full-stack, no login)

`spring-agent-start` ships both the Java library stack and a Vue 3 admin frontend
(`web/vue-vben-admin/apps/web-antd`) wired to the REST layer under `/api/v1/*`.
Everything defaults to "just work" locally, no login, no user model.

**1. Start the backend** — the runnable module `agent-start-example` already
imports `agent-start-web`, so its REST endpoints come up automatically on port
18090. The default profile uses embedded **H2**, so you don't need any external
database to try it. Switch to Postgres with `--spring.profiles.active=postgres`.

```bash
mvn -pl agent-start-example -am spring-boot:run
# → http://localhost:18090/api/v1/health
# → http://localhost:18090/api/v1/system/info   (probes which modules are loaded)
```

Verified with the shipped H2 config: `Started SpringAgentExampleApplication in
1.81 seconds`. A workflow SSE run streams `run-start → step (per node) → result`
in real time via `POST /api/v1/workflows/run-graph/stream`.

**2. Start the frontend** — the admin app has a vite proxy that forwards `/api` to
port 18090, so no CORS / URL surgery is needed.

```bash
cd web/vue-vben-admin
pnpm install         # first time only
pnpm dev             # opens http://localhost:5666
```

Open the browser: the "总览" landing page probes `/system/info` and tells you the
first step (usually: register a model in `/model/list`). Then create a knowledge
base at `/knowledge/list`, or an agent at `/agent/list`, or paste a workflow JSON
into `/workflow/playground` to watch its steps stream live via SSE.

### Docker (one command, Postgres + backend + frontend)

For a fuller demo with a real Postgres:

```bash
docker compose -f docker/docker-compose.yml up
# → frontend:  http://localhost:5666
# → backend:   http://localhost:18090/api/v1/health
# → postgres:  localhost:5432 (user postgres / pw difyai123456)
```

The Dockerfile (repo root) also works standalone if you only want the backend:
`docker build -t spring-agent-start . && docker run -p 18090:18090 spring-agent-start`.

## Build & test

```bash
mvn clean install            # build + test everything
mvn -pl agent-start-model -am test    # one module
```

Integration tests use an in-memory H2 database (auto-created from each module's
`db/*-schema.sql`). Tests that need a live LLM run against a local **Ollama** server and
**skip automatically** when it is not reachable (set `OLLAMA_BASE_URL` / `OLLAMA_TEST_MODEL`).

## Persistence

Each module ships portable DDL under `src/main/resources/db/*-schema.sql` (H2 + MySQL).
Tenancy is opt-in: a blank `tenant_id` defaults to `"default"`. Configure the credential
encryption key in production:

```yaml
spring-agent:
  model:
    encryption-secret: ${AGENT_SECRET}   # change me!
```

## Extension points at a glance

Everything worth swapping is a bean-discovered SPI. Publish a bean, drop the jar in — no
config, no code changes elsewhere.

| SPI | Where | Purpose |
|-----|-------|---------|
| `ModelProvider` | `agent-start-model` | Add a new LLM/embedding vendor |
| `VectorStoreFactory` | `agent-start-knowledge` | Add a new vector database |
| `DocumentReader` | `agent-start-knowledge` | Add a new file format |
| `Chunker` | `agent-start-knowledge` | Add a new chunking template |
| `Reranker` | `agent-start-knowledge` | Add a new post-retrieval scoring strategy |
| `NodeExecutor` | `agent-start-workflow` | Add a new workflow node type |
| `AgentStrategy` | `agent-start-agent` | Add a new agent reasoning loop |
| `Tool` / `ToolProvider` | `agent-start-tools` | Add a new tool or a bulk source (MCP, OpenAPI, plugin registry) |
| `TriggerDispatcher` | `agent-start-trigger` | Bind a trigger to something other than a workflow (e.g. an agent) |

## Roadmap

Implemented and tested: model management, knowledge/RAG, workflow orchestration, the
**tool/connector ecosystem** (`Tool` SPI + built-in tools + Spring AI `ToolCallback` adapter + a
`ToolProvider` plug-point for MCP/OpenAPI/plugin sources), and an **enterprise agent runtime**
(ReAct + function-calling strategies, JDBC-persisted conversation memory, multi-agent delegation,
human-in-the-loop tool approval). A unified **PostgreSQL** deployment is supported, including a
self-contained JDBC vector store (no pgvector extension required).

Triggers/automation (webhook, cron, event → async workflow runs with history + replay) and
**LLMOps** (per-call token/cost/latency metering, persisted and aggregated per model) are also
implemented and tested.

The agent runtime also includes a **plan-execute** strategy and **semantic vector memory**
(`spring-agent.agent.memory=vector`, backed by the knowledge module).

**MCP client** is supported too: configure stdio or HTTP MCP servers under
`spring-agent.tools.mcp.servers[*]` and their tools join the registry automatically
(`McpToolProvider` → `AgentTool`), so agents call them like any built-in tool.

Planned next:

- **Async approval resume** — persist a paused agent run and resume it after a human decision.
- **Distributed tracing** — unify the existing per-run signals (`StepRecord`, `AgentStep`, trigger invocations, LLM call records) into OpenTelemetry spans.

## License

Apache-2.0 (intended).
