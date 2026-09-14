# Knowledge Wiki 与图检索扩展设计

日期：2026-09-11。状态：架构设计，尚未实现。适用后端：spring-agent-start；前端：C:/chenhuanmou/vue-agent-start。

完整性补充与代码现状见 [多路构建与查询完整性审计](knowledge-wiki-graph-completeness-audit.md)，包含上传兼容性、模型/提示词修订、Neo4j 数据入口、部署脚本与端到端验收。两份文档都不代表功能已经实现。

## 1. 已确认的目标

- 服务器端原生实现，不部署或依赖 nashsu/llm_wiki 桌面应用、HTTP API 或 MCP。
- 参考资料分析、页面编译、来源关联、增量合并和维护检查的设计思路，自行实现。
- 在现有知识库体系上增加 Wiki、Neo4j 图检索；Neo4j 使用 Spring Data Neo4j。
- 用户创建知识库时选择查询方式，创建后不能更换。既有知识库保持 RAG。
- 选择三路增强的知识库，每次查询并行执行 RAG、Wiki、Graph；不能依赖前一路结果才启动后一路。
- 用 Spring Bean 自动发现的 SPI 分离公共编排和具体实现，符合现有 starter 模式。

## 2. 核心决策

### 2.1 固定查询方案，不把三种能力塞进 RetrievalMethod

引入不可变的 QueryPlan：由一个或多个 provider 绑定组成。providerId 使用可扩展字符串，不要求新增实现就修改核心枚举。

系统内置 rag、wiki、graph-neo4j。前端展示后端提供的方案卡片，例如原文检索、Wiki 检索、图谱检索、三路增强；后端也可以提供二路组合。组合是计划数据，不是第四个实现类。

QueryPlan 保存 planSchemaVersion、bindings(providerId、implementationVersion、algorithmId、indexBinding)、planHash。profileId 只是创建预设的来源，查询执行持久化快照，不重新解析可能已变化的预设。

创建时固定通道集合和各通道查询算法（包括已有向量/关键词/混合 method）；创建后允许调整 topK、阈值、上下文预算等不改变方式的参数。检索请求不能通过 method/providerIds/profileId 临时绕过固定方式。若后续业务决定允许调算法，应另做明确的产品决策。

冻结不等于禁止升级部署软件：implementationVersion 描述兼容契约；同契约补丁不变更方案，不兼容版本拒绝加载绑定并提供迁移流程。

方案变化通过新建知识库、复制原始资料、构建和验证完成；原知识库不原地切换。

### 2.2 目标模块边界

| 模块 | 职责 | 禁止依赖 |
|---|---|---|
| agent-start-knowledge-core（新增） | Dataset、SourceRevision、SPI、ProviderRegistry、QueryPlan、任务/版本编排、并行查询、融合、证据模型 | 具体 provider、Neo4j、工作流、Agent |
| agent-start-knowledge-rag（新增） | 现有 HybridRetriever、分块检索索引、RAG Provider | Wiki、Neo4j |
| agent-start-knowledge-wiki（新增） | 页面/修订/来源关系、编译/合并/维护、Wiki Provider | Neo4j、Agent、Workflow |
| agent-start-knowledge-neo4j（新增） | 图投影、SDN 映射、参数化 Cypher、Graph Provider | Wiki 实现类 |
| agent-start-knowledge（保留兼容入口） | 保留现有 API，默认组合 core + rag | 强制绑定 wiki/neo4j |
| agent-start-web / workflow / completion | HTTP、工作流和聊天适配 | 直接访问图 Repository |

依赖方向：common/persistence/model → knowledge-core → 三个 provider → 宿主装配。兼容入口依赖 core/rag；server 按需引入 wiki/neo4j。

迁移保留 io.github.aigoodle.knowledge 的现有公开类型/构造入口，不能复制同包同名类到多个 JAR。先移类与依赖，再通过兼容 facade 委托；以现有跨模块编译和测试证明兼容性。

原文解析属于公共资料摄入，RAG 特有分块、向量检索属于 RAG 实现。公共解析器可先随 core，后续确有依赖体积需要再拆 parser 模块，避免一开始过度拆分。

Neo4j 不是 VectorStoreFactory 的一个名字：它提供关系路径与证据查询，属于知识查询 provider。已有向量库 SPI 继续服务向量索引。

## 3. SPI 契约草案

以下是接口设计，不是已经存在的可调用 API。

```java
public interface KnowledgeQueryProvider {
    ProviderDescriptor descriptor();
    void validateBinding(ProviderBinding binding);
    ProviderQueryResult query(KnowledgeQueryContext context);
}

public interface KnowledgeProjectionProvider {
    String providerId();
    ProjectionBuildResult build(ProjectionBuildContext context);
    void delete(ProjectionDeleteContext context);
}

public interface KnowledgeResultFusion {
    KnowledgeQueryResult fuse(KnowledgeQueryContext context,
                              List<ProviderQueryResult> results);
}
```

读写接口分离；未来只读外部实现不必提供 build。描述符声明支持的算法、需要的模型类型、配置项、是否可构建和兼容版本。

SourceRevisionReader 提供授权后的不可变解析内容。可增加 KnowledgeFactExtractor 共享实体/关系/证据抽取；输出普通领域对象，不引用 WikiEntity 或 SDN Node。

Registry 通过 ObjectProvider/有序 Bean 列表发现实现，重复 providerId 必须报错，禁止依赖注册顺序悄悄覆盖。默认 bean 使用具体类/名称的 ConditionalOnMissingBean，不能按共同 SPI 条件把另外两个实现屏蔽。

三个实现分别提供 AutoConfiguration.imports。未安装 Neo4j starter 的宿主能独立启动和使用 RAG；已选 Neo4j 的库如果缺少实现，则返回明确不可用状态，不能当作正常零命中。

创建前先验证 provider 注册、契约版本、模型归属、配置有效性和基础设施就绪。能力发现接口分别返回 installed/configured/ready/reason，临时健康状态不用于篡改已持久化的方案。

## 4. 查询流程与结果契约

```text
认证租户与知识库授权
  → 读取不可变 QueryPlan 和本次发布快照
  → 为各 provider 分配预算
  → 并行查询
      rag          → 原文片段
      wiki         → 主题页面/段落
      graph-neo4j  → 关系路径/原文证据
  → 校验证据权限与有效性
  → 稳定排序、RRF、按证据去重、可选统一重排
  → 上下文预算裁剪
  → 返回命中、来源、各通道状态
```

第一版 SPI 使用同步领域接口，编排层用 Spring 管理的专用执行器并行调度；Java 21 虚拟线程也必须加请求/通道级并发上限。驱动请求超时、池限制、绝对 deadline 和取消句柄配套实现，不能仅 CompletableFuture 超时而让底层查询继续堆积。

全部 I/O 从 WebFlux event loop 移出；异步任务显式传 tenantId、datasetId、actor/scope、deadline，不依赖线程本地上下文传播，也不跨线程共享事务。

每路输出 ProviderQueryResult(providerId,status,elapsedMs,hits,errorCode,indexRevision)。status 区分 SUCCESS、EMPTY、NOT_READY、TIMEOUT、FAILED、CANCELLED。

统一 KnowledgeHit 包含稳定 hitId、providerId、kind(CHUNK/WIKI_SECTION/GRAPH_PATH)、datasetId、title、content、providerRank、原始分数、融合分数、evidenceRefs、wikiRevisionId、graphPath。图路径节点及边随 DTO 返回，用于前端解释。禁止为 Wiki 和图路径伪造 segmentId。

不同通道分数不可直接相加。初始融合采用加权 Reciprocal Rank Fusion；RRF 常数和权重属于可测的调优配置。只有同一个证据键才能跨通道累加；不相同对象保留各自贡献。Wiki 摘要和其原文不能当成两份独立事实证据，保留 lineage 并做证据组去重/限额。

不保证“三路一定更准”：图谱的邻居噪声、Wiki 信息损失和重复来源都需要评估。

默认部分成功可返回可用通道，但 response.degraded=true 且明确列出失败通道；全部失败返回错误；真正无命中返回 EMPTY。临时失败不改变查询方案。可提供严格全部通道成功的执行策略。

跨知识库查询为每个库读取自己的固定计划，控制 dataset×provider 扇出上限，做全局最终 topK。禁止由单次查询选择另一套方案。

## 5. 资料、Wiki 和图的构建

### 5.1 真源与投影

原始资料和 SourceRevision 是真源；RAG 索引、Wiki 页面与 Neo4j 图为有版本的派生数据。Wiki 人工补充需成为有身份的输入/修订，不能只留在向量索引。

关系库保存 Dataset、SourceRevision、QueryPlan、发布快照、任务、WikiPage/Revision、EvidenceRef。Neo4j 保存可重建的关系投影，不承担唯一的原文/任务记录。

EvidenceRef 使用 tenantId + datasetId + documentId + sourceRevisionId + blockId/页码/范围 + quoteHash；segmentId 仅是可选索引定位信息。解析重跑创建新解析版本，旧证据保持可定位。

### 5.2 可靠任务

```text
保存原始资料版本 + outbox（同一关系库事务）
  → 分发选中 provider 的构建任务
  → 各通道生成对应投影
  → 校验输出和证据
  → 标记各通道版本 READY
  → 发布 ReleaseManifest
```

第一版允许复用现有 MQ/本地 worker 的运行方式，必须以数据库任务为依据。幂等键包含租户、库、资料版本、provider、构建配置版本。租约、重试、恢复、死信、取消、消耗记录由公共任务编排负责。

同步入库、异步 DocumentIngestionRunner、重新解析、文档/片段修改、禁用和删除、整库删除，都要接入生命周期。

对片段人工编辑明确政策：第一版视为 RAG 索引修正，不自动改写原文或更新 Wiki 事实；界面说明作用范围。若希望影响三路，则提供“修订来源资料”操作并创建 SourceRevision，不能混用两个含义。

### 5.3 Wiki 编译

资料结构化分析 → 读取相关旧页 → 生成结构化页面变更 → 合并/冲突检查 → 引用验证 → 修订持久化 → Wiki 索引 → 就绪。

复用 llm_wiki 的思路：purpose/schema、两阶段生成、长文检查点、来源并集、冲突记录、目录程序维护。自行实现 Java 服务，不拷贝桌面状态管理或提示词源码。

Wiki 按主题、实体、来源摘要、综合页等类型组织。页面拥有稳定 pageId，标题与 slug 不作为唯一身份；乐观版本检查避免并发覆盖。维护 index/backlinks 不依赖模型输出。合并失败保留旧版，不以新正文直接覆盖旧知识。

Wiki Provider 使用页面标题、别名、正文的关键词/可选向量召回，返回相关章节；页面索引与 raw chunk 分开命名空间。向量存储基础设施可复用，但查询/结果仍分通道。

### 5.4 Neo4j 构建与版本

Graph 可从 SourceRevision 的结构化事实独立构建，不能只消费 Wiki 输出，否则图检索会成为 Wiki 的派生链路而非独立通道。Wiki→实体映射是可选增强。

实体消歧以库内稳定 canonicalId、类型、别名为依据；同名不自动跨库合并。事实抽取保留支持证据、否定/不确定性和有效时间。相互矛盾的事实分别保存，不让模型选择性覆盖。

ReleaseManifest 描述同一个 sourceSetRevision 下的 ragIndexRevision/wikiRevisionSet/graphGeneration。全部所选通道 READY 后发布。用写时复制/增量 manifest 表达未变化的页面和证据，不要求每次全库重算。

各存储先写不可见版本并验证，再以关系库一个 manifest 指针发布。查询开始固定 manifest，各通道按对应版本读取。未完成新版本时继续旧版本并显示构建状态；首次构建未完成返回 NOT_READY。活动查询有保留期/租约，旧投影不能发布后立即清理。

删除、禁用、权限撤销必须立即在公共授权/有效证据层失效，不等待异步图删除。Wiki 若依赖失效证据而尚未重编译，相关章节或整页退出查询；Graph 遍历每一步也必须过滤。合成结果不能仅在引用显示时隐藏失效来源。

PostgreSQL/MyBatis 与 Neo4j 使用各自事务。引入 Neo4j 事务管理器后，显式保留关系库事务管理器为既有默认，图写使用命名的 neo4jTransactionManager；启动测试覆盖 Boot 条件自动配置，避免已有关系库事务自动配置退让。使用 outbox/幂等/manifest 协调，不把一个 @Transactional 当成跨库原子事务。

## 6. Neo4j + Spring Data Neo4j

新增模块依赖 spring-boot-starter-data-neo4j，SDN/Driver 使用现有 Spring Boot 3.5.6 BOM 管理的兼容版本，不直接采用网页上的最新 8.x。上线前固定 Neo4j 服务端版本并做矩阵验证。

SDN @Node/@RelationshipProperties 映射有限聚合，Repository 处理简单实体操作，Neo4jClient 处理有界路径、批量投影和查询 DTO。避免读取/保存整个知识库关系图。Neo4j Java/SDN 类型不穿透 SPI。

建议模型：

```text
(:KnowledgeEntity)-[:SUBJECT_OF]->(:KnowledgeFact)-[:OBJECT]->(:KnowledgeEntity)
(:KnowledgeFact)-[:SUPPORTED_BY]->(:Evidence)
(:Evidence)-[:FROM_SOURCE]->(:SourceRevision)
(:WikiSection)-[:ABOUT]->(:KnowledgeEntity)  // 可选
```

Fact 节点承载 predicate、certainty、validFrom/validTo、generation、稳定业务 ID，便于一个事实多份证据、删除来源后重新计算有效性。所有节点/关系都带租户、库与版本范围，物理 ID/唯一约束覆盖作用域，不能仅 MERGE(name)。

查询：问句实体识别/别名匹配 → 租户与库内种子候选 → 固定一到二跳事实路径 → 证据回读 → 输出关系解释。hop 统计按事实连接定义，与 SUBJECT_OF/OBJECT 的物理边数区别开。

采用固定 Cypher 模板和参数绑定，限制种子数、路径数、每节点展开数及超时。第一版不让模型直接生成任意 Cypher。全文索引需要显式查询；中文分词和别名召回单独验收，不能默认英文 analyzer 能解决中文。

全文候选不能先做全租户全局 topK 再过滤：必须在作用域内确定 topK，或采用可证明正确的分区/迭代候选策略；路径中的节点、事实和证据都核对范围，避免跨租户跳转。

参考：
- https://docs.spring.io/spring-data/neo4j/reference/appendix/neo4j-client.html
- https://docs.spring.io/spring-data/neo4j/reference/getting-started.html
- https://neo4j.com/docs/cypher-manual/5/indexes/semantic-indexes/full-text-indexes/

## 7. 创建、不可变约束与 API

建议 API（设计中的接口，保留现有全局 /agent-start 前缀）：

```text
GET  /knowledge/query-profiles         能力驱动的创建方案
POST /datasets                        创建并冻结方案
PUT  /datasets/{id}                    只更新允许修改的字段
POST /datasets/{id}/query              新的统一结果与通道诊断
GET  /datasets/{id}/projections        各通道构建与发布状态
POST /datasets/{id}/rebuild            重建固定方案的投影
GET  /datasets/{id}/wiki/pages         页面浏览（按 provider 能力显示）
GET  /datasets/{id}/graph              图浏览（与查询路径独立）
```

创建请求提交 queryProfileId、能力版本和各 provider 的必要参数。服务端解析/验证并保存 QueryPlan 快照，不能信任前端传任意实现类名。

首期提供 RAG、Wiki、Graph、三路方案；二路组合通过注册配置提供，不硬编码所有排列。profile 不可用时前端展示原因并禁选，后端重新校验。图连接凭据由宿主管理，前端选择有权限的逻辑连接，不提交 URI/密码。

RAG 高质量方式需要 Embedding；Wiki 需要编译模型，可选查询 Embedding；Graph 需要图服务及事实抽取配置。模型需求由 provider 声明，不沿用当前“所有 HIGH_QUALITY 都必须一个 embeddingModelId”来约束纯图模式。

更新接口遇到方案字段变化返回 knowledge_query_plan_immutable；显式识别尝试修改的字段，不能只在 DTO 去掉字段后静默忽略。服务层、更新 SQL 白名单共同限制；单独不可变计划表不提供 update API。并发测试确认不会被元数据更新覆盖。

历史数据迁移为 rag 绑定，按原有 method/默认值生成快照，不批量改为三路，也不自动触发新的模型调用。原 /retrieve 数组接口维持旧 RAG 客户端兼容；新增多通道消费者使用 /query。旧接口遇到增强库必须明确返回版本不支持，不能伪造 chunk 或无提示丢掉两路结果。Workflow 升级为统一入口，保持 result 文本字段，新增 hits/citations/channels/degraded。

## 8. Vue 组件库改造

实际路径根为 C:/chenhuanmou/vue-agent-start。Vben 是宿主，不把功能写到 Vben 页面。

| 文件 | 变更 |
|---|---|
| src/client/knowledge.ts | profiles、query、projections、Wiki/Graph 访问契约 |
| src/knowledge-hub/types/dataset.ts | 查询方案摘要、创建参数、能力描述 |
| src/knowledge-hub/types/api.ts | 可选扩展能力，兼容原有宿主实现 |
| src/knowledge-hub/adapters/springAgentStart.ts | 映射新接口、命中/来源/图路径 |
| src/knowledge-hub/components/CreateDatasetWizard.vue | 创建时选择方案，展示所需模型与配置 |
| src/knowledge-hub/components/KnowledgeHubApp.vue | 传递并保存方案，空知识库创建也走同一配置步骤 |
| src/knowledge-hub/components/DatasetSettingsPanel.vue | 固定方案只读，展示“创建后不可修改” |
| src/knowledge-hub/components/RecallTestingPanelV2.vue | 按已固定方案查询，展示三路贡献/耗时/降级 |
| src/knowledge-hub/components/DatasetDetailDrawer.vue | Wiki 页、图谱和投影状态入口 |

特别注意：当前 onEmptyPromptConfirm 直接创建 ECONOMY 数据集，不能留下绕开方案选择的快捷入口。创建向导关闭/重开、接口返回失败均需保留或明确重置一致的方案状态。

现有 RetrievalMethodPicker 在设置和召回页面也能修改 method；新约束下相应方式只读，不能只禁用新的卡片而保留旧组件绕过。topK/阈值等可调参数单独呈现。

用户文案使用“原文检索 / Wiki 检索 / 图谱检索 / 三路增强”，Neo4j/SDN 等基础设施名称仅在管理诊断中显示。

## 9. 分阶段交付与验收

1. 核心拆分与兼容：移出 core/rag，SPI 注册、统一结果、RAG 适配、既有 starter 编译和 RAG 回归通过。
2. 固定方案闭环：计划落库/迁移、更新拒绝、能力 API、Vue 普通/空库创建、只读设置。只展示已可用方案，不能在实现未完成时创建无法构建的三路库。
3. Wiki：来源版本、outbox、编译/修订/引用、页面查询、失败恢复，使用假模型做确定性测试，真实模型另做可选质量评估。
4. Neo4j：SDN 投影、唯一约束、事实证据、参数化路径检索、作用域过滤、事务隔离；真实 Neo4j 容器集成测试不能以 mocked Repository 代替。
5. 三路：同输入并行、deadline、RRF/证据去重、manifest 发布、跨库扇出、端到端 Vue/工作流/聊天引用链路。

关键验收：
- 仅安装 RAG 的宿主不要求 Neo4j；每个 provider 可被宿主 Bean 替换。
- 直接 REST、服务调用和旧 method override 都不能修改已固定方案。
- 使用同步屏障证明三路同时启动，避免仅用耗时断言；慢路不拖延整体 deadline。
- 相同名称的不同租户实体、图路径中间节点、Wiki 来源均不串库。
- 资料重解析后旧引用仍可定位；删除/禁用立即停止泄露派生信息。
- 重试不重复建页/事实；并发编辑不丢数据；跨库写到一半崩溃可恢复。
- 单路失败、全部失败、全部零命中、未完成构建分别可辨认。
- 现有 RAG 用例不回退；新增跨文档综合、关系路径、时间冲突、中文实体集衡量准确率、引用正确率、延迟和调用成本。

## 10. 当前结论

这一设计扩展的是“知识投影和查询能力”，而不是给现有向量库多加一个配置项。关键实现顺序是先建立可替换的 SPI 和不可变方案，再完成两个 provider 的实际构建/查询，最后开放三路方案。本文没有执行数据库迁移、安装 Neo4j 或修改业务代码。
