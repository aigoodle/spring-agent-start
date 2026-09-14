# Knowledge 多路构建与查询完整性审计

日期：2026-09-11。范围：本地 Spring、Vue 源码及部署脚本的静态核验。

结论：当前只有既有 RAG 的实现基础，Wiki/Neo4j provider、固定查询计划、多路构建和并行融合尚未实现。knowledge-wiki-graph-spi.md 是目标设计，不能据此宣称已经支持三路。本文补齐设计遗漏与交付验收要求；下列新增文件、配置和接口均是待实现契约。

## 1. 实际状态与阻断项

| 环节 | 当前状态 | 证据/缺口 |
|---|---|---|
| Vue 上传 | 已有 FormData 上传 | src/client/knowledge.ts 的 uploadDocument 返回 void，未暴露文档状态/任务回执 |
| server 上传 | 存在兼容性断点 | server 强制 WebFlux，DatasetDocumentController 上传/预览使用 MultipartFile；未发现 FilePart 对应实现 |
| 文档解析与 RAG 入库 | 有实现，未做本轮运行验收 | DocumentIngestionService → StructuredDocumentChunker → IndexingService；同步与异步分别处理 |
| 原始文件可靠保存 | 需要调整顺序 | addFile 先解析/submit，后写 source bytes/checksum；异步事件接入前必须先持久化来源版本 |
| 上传成功判定 | 不完整 | 同步 ingestion 捕获失败并返回 FAILED 文档，controller 仍使用 ApiResponse.ok；前端 void 接口无法按文档状态判定构建成功 |
| Wiki 调用模型 | 尚未实现 | ModelService.getChatClient(tenantId,id) 可复用，但没有 Wiki 编译服务及模型绑定 |
| Wiki 提示词 | 尚未实现 | model 模块已有 PromptTemplateService；当前模板可原地修改，未见 Wiki 阶段配置/不可变修订 |
| Wiki 页面与检索索引 | 尚未实现 | 没有 Wiki 页面表、编译/发布/引用服务或 provider |
| Neo4j 数据入口 | 尚未实现 | 无 SDN 模块、图构建任务、事实抽取/投影写入、Cypher 迁移 |
| 多路并行与融合 | 尚未实现 | KnowledgeService 调用单个 HybridRetriever；跨库 datasetIds.stream() 也是串行调用 |
| 创建时固定方式 | 尚未实现 | Dataset/创建 DTO/Vue 尚无 QueryPlan；仅有设计文档 |
| Docker Neo4j | 尚未实现 | docker/ 与 pgsql/ Compose 都无 Neo4j 服务 |
| Docker 构建目标 | 与服务器目标不一致 | Dockerfile 打包/复制 agent-start-example，不是 agent-start-server |
| Docker 前端目录 | 当前工作区无法满足 | Compose 指向 web/vue-vben-admin，仓库内不存在该目录；真实前端与组件库是同级独立项目 |
| 数据库默认配置 | 需要统一 | pgsql Compose 与 server 默认连接密码不同；两套 Compose 的数据库名也不同，不能混用说明 |
| 开箱多服务运行 | 尚未验收 | server 配置使用 Redis，当前基础 Compose 无 Redis；应明确是否启用依赖该服务的能力 |

已核对代码路径均在本地仓库。未执行真实模型、Neo4j、Docker 或完整 Maven 测试。当前 PowerShell PATH 未发现 docker 与 mvn，不等价于已穷尽机器上的安装位置。

## 2. 完整的数据流程

```text
Vue 创建知识库（固定 QueryPlan，选择构建模型和提示词）
 → 上传文件
 → 认证/大小与类型校验/原始文件持久化
 → SourceRevision + outbox 提交
 → 解析任务（得到稳定 block、页码、表格、标题、警告）
 → 读取固定方案，为已选通道创建 projection job
    ├─ RAG：chunk → embedding → chunk/vector index
    ├─ Wiki：分析 → 读取相关旧页 → 生成/合并 → 校验 → page revision → section embedding/index
    └─ Graph：实体/事实抽取 → 证据校验/消歧 → Neo4j 版本投影
 → 等待所选通道 READY → 发布同源版本 manifest
 → 查询：按固定计划并发调用 provider → 证据校验 → 融合/重排 → 上下文/引用
```

“多路”不等于把相同文本向量化三次：RAG 为原文片段建向量，Wiki 为编译后的主题章节建独立向量；Graph 建实体事实关系。Graph 可选实体向量召回，但它不能替代关系遍历。

解析缓存由 sourceRevision+parserVersion 标识，供各通道读取；不重复从原始文件执行三遍解析。二进制/大文档使用受控临时文件和持久化引用，限制内存，不把整个文件/Base64 在任务消息中传来传去。

解析失败、不支持类型、扫描 PDF 缺少 OCR、表格结构损失必须作为可见状态/警告。OCR 用可选解析 SPI 与能力检测，不能把空文本当成成功入库。

生产 server 提供 FilePart 上传/预览适配，将阻塞解析移交后台；MVC 宿主保留 MultipartFile 适配，拆开 Controller 并按 WebApplicationType 装配，避免相同路径重复注册。

上传返回 UploadReceipt(documentId,sourceRevisionId,ingestJobId,status)。接受异步任务不等于全部构建完成。前端轮询或订阅任务与各投影状态；一批文件部分失败可单独重试，不重复建库。既有 void 上传 API 用新版本/可选方法兼容迁移。

## 3. 模型配置与提示词必须落到可执行配置

### 3.1 配置边界

QueryPlan 固定“怎么查询”，BuildConfigRevision 管“怎样产生知识”。模型/提示词可以保存新构建配置修订，但不能偷偷修改当前已发布投影或改变固定查询通道；需要显式重建并发布。

建议各阶段绑定：

| 阶段 | 模型配置 | 提示词/输出 |
|---|---|---|
| RAG 向量化 | embeddingModelId、维度、距离度量 | 无生成提示词 |
| Wiki 分析 | analysisModelId | analysisPromptRef → 结构化资料要点、实体、证据定位 |
| Wiki 页面生成 | generationModelId | generationPromptRef + purpose + schema → WikiChangeSet |
| Wiki 合并 | mergeModelId（可继承生成模型） | mergePromptRef → 基于指定旧修订的合并变更 |
| Wiki 校验/维护 | reviewModelId（可选） | lintPromptRef → 冲突/过期/待核验事项 |
| Wiki 向量化 | wikiEmbeddingModelId | 页面章节独立向量命名空间 |
| Graph 抽取 | extractionModelId | extractionPromptRef + ontology → Entity/Fact/Evidence 对象 |
| Graph 查询消歧 | queryModelId（可选） | 仅生成受约束的实体/关系条件，不返回任意 Cypher |
| 最终问答 | 现有 Agent/Workflow 模型 | 从统一检索结果组织引用与回答 |

默认允许分析/生成/合并复用一个模型，界面提供高级覆盖。使用既有 ModelService 解析租户拥有的模型，不在 Wiki 配置再保存 API Key。记录模型参数、实际模型身份、提示词哈希、输入来源/页面修订和 token 消耗。

### 3.2 提示词管理

复用位于 model 模块的 PromptTemplateService 作为模板选择/编辑入口。构建配置保存模板 ID + 不可变内容快照/版本 + 哈希；任务启动固定快照，重试不能读取后来修改的内容。

阶段模板声明输入变量、长度限制、输出 Schema。采用当前模板变量语法 {{#name#}}，提供变量校验和渲染预览；缺变量/非法占位符在调用模型之前报错。

内置默认模板随 provider resources/prompts 发布，可选从模板库覆盖，不要求每个用户从零写提示词。purpose/页面 schema/图 ontology 各自有版本，不能只在一个自由文本系统提示词里混为一体。

任务状态包含 PROMPT_RENDERING、MODEL_RUNNING、OUTPUT_VALIDATING、PERSISTING、INDEXING、READY；输出不合法可有限次数修复，超限 FAILED。不得无限重试模型。

WikiChangeSet 至少包含目标 pageId/type、baseRevision、sections、sourceEvidenceRefs、links、conflicts。用 DTO/JSON Schema 校验、链接/来源白名单、乐观锁后落库。模型不能指定任意磁盘路径或数据库语句。

生成成功 ≠ 发布成功：页面持久化或向量写入失败时保持上一发布版；重试复用已验证的模型输出，避免重复付费。

Embedding 缓存键包含文本哈希、预处理版本、模型/维度；RAG/Wiki 的逻辑命名空间和版本必须隔离。更换向量模型不得沿用旧索引，需完整重建对应发布版本。

## 4. Neo4j 的数据入口与启动初始化

图输入来自解析后的 SourceRevision；GraphProjectionProvider 消费 GraphFactSet，不把全文字符串直接保存成一个节点冒充图谱。Wiki→实体关联为可选后续投影。

GraphFactSet 定义 entities(canonicalId,type,name,aliases)、facts(subjectId,predicate,objectId,qualifiers)、evidence(sourceRevisionId,blockId,quoteHash)、scope 和 generation。

入口执行：模型/规则抽取 → 输出校验 → 证据实际存在性检查 → 库内实体消歧 → 有界批量 UNWIND/MERGE → 构建校验 → READY。稳定主键覆盖 tenantId/datasetId/generation，重放不重复。不能依赖 Neo4j 内部 elementId 作为业务永久 ID。

通过 SDN Repository/Neo4jClient 写入与查询；批量查询使用命名参数。事实/证据与关系均携带作用域。禁用、删除和重建都有对应投影任务和幂等处理。

图查询必须验证实体种子、路径中间节点、事实与证据范围。返回可解释路径及来源，不只是节点名称。实际事实抽取正确率单独测试，格式校验通过不代表事实正确。

图 Schema 脚本包含业务唯一键约束、scope/generation 查询索引、需要的全文索引以及 schemaVersion 记录。先执行 schema migration，再开放 graph provider 的 ready 能力；迁移失败阻断相应能力。

## 5. 多线程调用、配置与融合

查询服务读取固定方案，创建不可变上下文，然后一次性提交所有通道任务到 Spring 管理的专用 ExecutorService。Java 21 虚拟线程可用，但仍必须限制整体和单通道并发、模型并发及连接池容量。

入库 worker 与查询 worker 分离，避免大量 Wiki 生成饿死在线查询。禁止每个请求新建未关闭线程池，禁止 parallelStream/commonPool 承担不可控阻塞任务。

运行配置需要有清晰绑定类及默认值：overallTimeout、providerTimeouts、maxConcurrentQueries、maxFanOut、maxBuildWorkers、maxModelCallsPerJob、maxTokensPerJob、retryPolicy、fusionWeights、contextBudget。

底层 HTTP/Neo4j statement timeout 配合查询 deadline，取消要传播到底层；只让 Future 超时并不会自动终止数据库/模型请求。执行器随 Spring 关闭，记录剩余任务并允许恢复。

tenantId/actor/datasetId/manifest 显式传递，不能指望异步线程继承 UserContextHolder 或关系库事务。

结果按通道记录 rank、rawScore、latency、status、revision，再执行证据级去重、RRF、可选统一重排、上下文预算裁剪。原文、Wiki 和图相同来源不能被误认为多份独立佐证。

融合响应必须区分无命中、未就绪、超时、错误和成功；返回 degraded 与 warnings。所选通道全部失败时返回错误，不调用模型编造“基于知识库”的答案。单通道临时不可用不修改 QueryPlan。

## 6. Docker 与脚本交付清单

当前脚本不具备三路启动能力。目标交付应至少包含：

| 待交付文件 | 职责 |
|---|---|
| Dockerfile.server（或修正根 Dockerfile） | 用 Java 21 构建 agent-start-server，包含新模块及资源，完整 Maven reactor |
| docker/compose.knowledge.yml | Postgres、Neo4j、服务端；按实际 server 能力加入 Redis，RabbitMQ 可选 |
| docker/.env.knowledge.example | 数据库/Neo4j 凭据、端口、内存、镜像版本等变量名与说明 |
| server application-knowledge.yml | SPI 模块启用、Spring Neo4j 连接、构建/查询执行器参数；模型使用已有注册表 |
| pgsql/migrations/*knowledge*.sql | 计划、来源版本、Wiki、任务/outbox、发布 manifest 表和旧 RAG 迁移 |
| docker/neo4j/migrations/*.cypher | 图约束/索引/版本，幂等、失败非零退出 |
| scripts/knowledge/start.ps1 与 start.sh | 校验配置、构建、启动依赖、迁移、启动服务、就绪检查 |
| scripts/knowledge/verify.ps1 与 verify.sh | 上传固定样本、等待投影、三路查询、来源/隔离/恢复断言 |
| scripts/knowledge/stop.ps1 与 stop.sh | 停服务，默认保留数据卷 |
| docs/operations/knowledge-stack.md | 从空环境运行的准确命令、排错、备份恢复和升级说明 |

文件名称为交付建议，尚未创建这些可执行脚本。

启动顺序：Postgres/Neo4j 健康 → SQL/Cypher 迁移成功 → 后端就绪 → 配置所需模型 → 上传验收。首次启动没有模型配置时应提示“待配置”，不能虚报可创建三路知识库。

Neo4j 镜像固定经 SDN/Driver 集成验证的版本；使用命名卷持久化 /data，按需挂载 /logs；开发映射 7474/7687，容器内后端使用 bolt://neo4j:7687，不能用 localhost。连接配置使用 spring.neo4j.uri、authentication.username/password 等标准项。

健康检查以实际 Bolt/Cypher 轻量查询为依据，等待就绪后执行 migration。不能把 Cypher 文件挂到 PostgreSQL 风格 init 目录并假设 Neo4j 会执行。使用独立 one-shot migration service 或应用迁移器，成功后才启动依赖服务。

密码从环境/secrets 注入，脚本不能打印有效配置里的秘密。已有卷修改 NEO4J_AUTH 不等于完成数据库密码轮换。开发默认值、文档和 server 变量要一致。

构建不依赖宿主机 C: 绝对路径。vue-agent-start 是组件库，不能直接当作运行网站；server Compose 可先独立后端，完整 UI 部署需要真实宿主前端镜像/构建上下文，不能保留当前不存在的 web/vue-vben-admin 路径。

根 Dockerfile 的预缓存阶段目前缺少若干 reactor POM，并用 || true 掩盖失败；新脚本须移除掩盖式检查或明确将缓存优化与真正构建分开。正式构建阶段不得跳过缺模块/资源错误。

基础设施启停不得自动清卷。脚本必须支持重复执行、超时、失败非零退出、从任意 cwd 调用，并提供 PowerShell/Bash 对等行为。

Neo4j 官方依据：
- https://neo4j.com/docs/operations-manual/current/docker/docker-compose-standalone/
- https://neo4j.com/docs/operations-manual/current/docker/operations/

## 7. 前端完整性补充

创建向导按方案显示：RAG Embedding、Wiki 编译模型/purpose/schema/阶段提示词、图抽取模型/ontology。高级参数折叠，默认模板可用；图连接由管理员配置，用户选择逻辑连接。

设置页区分只读查询方式与可版本化构建设置。保存新提示词/模型后提示需要重建并显示待发布版本，不能让用户误认为已影响当前问答。

上传后展示每个文件的解析进度，以及 RAG/Wiki/Graph 各自进度和错误；提供失败通道重试。Wiki 页面可查看来源、修订和生成配置；图可查看事实与证据；召回页展示三路结果和融合结果。

兼容现有 KnowledgeHubApi 宿主：扩展能力可选，旧宿主只显示 RAG；后端不可用方案应禁选。普通上传、空知识库、追加文件、重新解析和重建都要覆盖。

## 8. 从空环境到查询的最低验收

1. 仅 RAG 安装可启动，不要求 Neo4j；全量安装能发现三个 provider，重复 ID/不兼容版本启动检查明确失败。
2. docker compose config -q、镜像 build、services health、migration 返回码均通过。不是仅看容器 running。
3. 注册 Chat/Embedding 模型并校验租户权限；缺模型、缺提示词变量、维度不匹配在构建前明确失败。
4. 创建三路库，REST/服务调用/旧 method override 都不能更换方式。
5. 上传 TXT、Markdown、PDF、DOCX（加表格/扫描 PDF 边界），确认原始版本可读、解析告警可见。
6. RAG 片段向量、Wiki 页面及章节向量、Neo4j 实体事实证据分别实际存在；不能只断言任务 COMPLETED。
7. 重启 worker 恢复任务，重复投递不重复写入；输出解析失败不发布；模型调用失败有受限重试。
8. 并发测试用 barrier 证明三路在任何一路完成前均已启动；测试 deadline、取消和任务资源释放。
9. 相同事实、跨文档主题、多跳关系问题有正确证据；原始分数不同仍稳定融合；过长上下文不超预算。
10. 停止 Neo4j 验证降级；全路失败验证错误；恢复图服务不需要更改知识库方式。
11. 修改资料与提示词后重建，新版本就绪前旧版一致可查；删除/禁用来源后所有派生内容立即停止作为有效证据。
12. 构造两个租户相同实体名称，验证图中间路径/Wiki 引用/向量均不串租户；重启容器数据仍可用。

确定性测试使用模型替身验证控制流和格式，真实模型质量测试单独报告模型/数据集/成本。Neo4j 集成测试需要真实实例；Docker、驱动、模型缺失时必须记录“未执行/未验证”，不能计为通过。

## 9. 审计后的交付顺序

先修服务器上传与服务端镜像目标 → 核心 SPI/不可变计划/数据迁移 → 模型提示词配置与任务版本 → RAG/Wiki/Neo4j 实际写入查询 → 多路并发融合 → Vue 全流程 → Docker/Cypher/SQL/启动与验收脚本 → 空环境端到端验收。

本轮是完整性审计，不是功能实现完成报告。架构文档和本审计共同作为实现与验收依据。
