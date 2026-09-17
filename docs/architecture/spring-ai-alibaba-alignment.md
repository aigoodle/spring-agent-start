# Agent Start 与 Spring AI Alibaba 的架构对齐策略

对比基线：本地 Spring AI Alibaba `c65a3eb5f`（2026-08-15，项目版本 `1.1.2.2`）。适配器依赖
与该源码版本一致；升级时必须重新运行 Runtime、HITL、可信上下文和流式输出契约测试，而不是
直接跟随其 Admin 数据模型。

## 产品边界

Agent Start 是可嵌入宿主 Spring Boot 系统的业务 Agent SDK 与 Connector Control Plane，宿主负责登录、组织、员工和租户主数据。Spring AI Alibaba 同时提供 Graph 执行引擎、Agent Framework、Studio 和完整 Admin 平台。两者有交集，但产品边界不同。

结论：不把 Spring AI Alibaba Admin 或 Agent Framework 作为 Agent Start 的必选运行时依赖。保持 Agent Start 的轻量模块和 SPI；对 Graph/Agent Framework 提供可选适配器，并学习其上下文、Hook、Checkpoint、可观测设计。

## 对比与决策

| 维度 | Spring AI Alibaba | Agent Start 当前 | 决策 |
|---|---|---|---|
| 嵌入方式 | Agent Framework/Graph Java API；Studio UI 可嵌入 | 独立 starter、Vue 组件库、前后端均可嵌入 | 保持现有优势 |
| 运行上下文 | `RunnableConfig.context`、`ToolContext` | `CurrentUser`、`UserContextHolder`、`AgentRunContext.requestVariables` | 建立统一快照并贯穿 Agent/Workflow/Tool/Connector |
| 扩展机制 | Hook + Model/Tool Interceptor | Bean SPI、Strategy、Decorator、ApprovalGate | 增加有序 Agent 生命周期拦截器，不复制其类层次 |
| 状态执行 | Graph、Checkpoint、多存储实现 | Workflow DAG、Memory、运行记录 | 借鉴 Checkpoint/恢复协议；暂不替换引擎 |
| 多 Agent | Sequential/Parallel/Routing/Loop | Strategy 与 delegation | 补正式组合 Agent 模型 |
| 人工介入 | Graph interrupt/HITL hook | ApprovalGate、消息 HANDOFF | 统一为可持久化暂停/恢复协议 |
| 前端 | Studio 调试 UI + Admin 完整平台 | 可嵌入 Vue 组件、业务 Hub | 不复制 Admin；加强 SDK props/events/slots 与宿主路由集成 |
| Connector/渠道 | MCP、Nacos、云生态为主 | 原生 Bot/Email/Webhook、企业消息账号和路由 | 继续作为差异化核心 |

## 集成策略

### 直接复用

- Spring AI 标准 `ChatModel`、`ToolCallback`、MCP 协议层。
- Micrometer Observation/OpenTelemetry 语义。
- 如客户已经采用 Spring AI Alibaba Graph，通过可选模块实现 `AgentRuntime` 适配，不复制源码。

### 学习设计，不直接依赖

- `RunnableConfig.context`：每次运行显式携带不可变上下文。
- Model/Tool Interceptor：模型与工具调用的重试、降级、限额、PII 和选择策略。
- Checkpoint Saver：长运行任务可暂停、恢复和迁移。
- Studio embedded mode：UI 是组件而不是强制独立控制台。

### 不复用

- Admin 的租户、用户、项目和权限模型。它会与宿主系统冲突。
- 第二套 Agent 定义、会话、记忆和工作流数据库。
- Alibaba Cloud/Nacos 强绑定作为核心依赖。

## 目标运行时上下文

宿主认证层创建可信 `CurrentUser`，Agent Start 不接受普通业务请求自行选择租户：

```text
Host Authentication / Message Account Binding
  -> CurrentUser(tenantId, userId, roles, scopes, extra)
  -> AgentRunContext / Workflow ExecutionContext
  -> ToolExecutionContext / ConnectorExecutionContext
  -> persistence, cache, audit and outbound messages
```

HTTP 同步入口由宿主过滤器写入 `UserContextHolder`；WebFlux 使用 Reactor Context；消息回调根据已绑定的运行时账号解析租户和员工，并在执行 Agent 时建立 `CHAT_SESSION` 上下文。控制器只能读取可信上下文，不能信任 query/body 中的 `tenantId`。

## 分阶段实施

### P0 可靠消息与安全边界

1. 渠道控制器全部改用可信运行时租户；跨租户单测覆盖读取、更新、删除和回复。
2. `agent_channel_event` 演进为追加式消息日志；入站、Agent 回复、人工回复通过 `reply_to_event_id` 关联。
3. 增加 Outbox：`PENDING -> SENDING -> SENT/FAILED`，幂等键、平台消息 ID、租约、指数退避。
   限流通过 `ChannelOutboundRateLimiter` SPI 按租户、Provider、运行节点和账号隔离；单节点默认
   使用内存实现，水平扩容的企业宿主应提供 Redis 或 API Gateway 共享配额 Bean。
4. 会话查询 API：游标分页、`conversation_id` 时间线、发送人类型与 ID。
5. 结构化内容：文本、图片、文件、语音及平台原始 metadata。

### P1 Agent 与客服

1. 引入 Agent 发布版本和不可变快照；连接绑定已发布版本或跟随通道策略。
   - 已实现 `ACTIVE / SUPERSEDED / DISABLED` 生命周期、回滚生成新快照、同一 Agent 并发发布行锁。
   - 渠道会话首次执行时原子固定 `agent_id / agent_version_id / route_reason / policy_version`。
   - `SUPERSEDED` 版本只是不再接收新会话；既有会话可继续复现。`DISABLED` 是显式停止开关。
   - 发布、回滚、停用以及租户/员工 Agent 绑定变更要求宿主注入管理员角色。
   - 上述管理动作与无密钥审计记录在同一事务内提交；审计失败时业务变更一并回滚。
   - 主 Agent 异常或未完成时可进入租户配置的 fallback Agent，运行结果记录实际 Agent、版本、降级原因与策略版本。
2. 增加有序 Agent/Model/Tool interceptor SPI，先实现限额、重试、PII 和审计。
   - 已实现 `AgentRuntimeInterceptor`，宿主 Bean 可按 `order()` 稳定包装 Native 与所有可选 Runtime。
   - 拦截器可失败关闭或转换请求，不需替换 Registry；具体限额、PII 和审计策略由宿主或后续可选 starter 提供。
   - Native ReAct、Plan、Function Calling、Workflow Tool Node、`ToolRegistry` 直接执行以及框架自动
     注册的 Spring AI `ToolCallback` 均进入统一 `ToolExecutionGateway`。Gateway 提供有序策略、并发准入、
     超时、仅幂等工具重试、输出上限和结果监听；自动回调会从宿主可信 `CurrentUser` 快照租户、员工、
     角色和 scope。Reactor、消息消费或作业系统可替换 `ToolExecutionContextProvider`，不要求采用本项目
     的登录或组织模型。
3. 会话状态机、接管人、处理组、租约锁、内部备注、暂停/恢复 Agent。
4. QQBot、飞书、钉钉、企业微信、Email 与 Webhook 通过统一轻量 SPI 满足入站、出站、健康和重连契约。

### P2 Native Runtime 强化

项目不再维护 Spring AI Alibaba Runtime 适配模块，避免双执行引擎带来的依赖冲突、语义漂移和
测试矩阵膨胀。Alibaba 项目继续作为架构对照基线，其优秀设计通过本项目自己的稳定协议落地：

1. 保留通用 `AgentRuntimeExtension` 与 `AgentRuntimeRegistry` SPI，允许企业宿主自行实现第三方运行时，
   但核心发布物只提供 Native Runtime；未知运行时发布和执行继续失败关闭。
2. Checkpoint、暂停/恢复、多审批 HITL、可信租户上下文和持久化运行事件由 Native Runtime 自主实现，
   不依赖 Alibaba 的序列化格式、数据库实现或版本发布节奏。
3. 编辑器通过 `/agent-runtimes` 动态发现宿主实际安装的扩展，不写死任何第三方运行时选项。
4. A2A、Nacos 等生态能力如有真实客户需求，以独立协议适配器实现，不进入核心依赖。

## 验收标准

- 宿主注入 tenant A 时，即使请求携带 tenant B，也只能访问 tenant A。
- 消息重复回调不重复运行 Agent；人工重复提交不重复发送。
- 每条出站消息都有发送人、触发消息、会话、幂等键和平台消息 ID。
- 进程在发送任意阶段退出后可以恢复，不丢消息且可判定是否重发。
- 六种原生消息通道通过同一组运行时、回调幂等和 Outbox 契约测试。
- 发布新 Agent 版本后，已有会话继续使用首次命中的快照，新会话使用最新活动版本；显式停用后旧会话必须走配置的降级策略或失败关闭。
- UI 只依赖 SDK client 和宿主传入上下文，不内置登录、员工或租户管理系统。

## 前端嵌入边界落实

`vue-agent-start` 的统一 Client 将 `getTenant` 定义为目录/展示提示而非授权来源，并默认不再发送
浏览器可伪造的 `X-Tenant-Id`。`ConnectorHubApp` 也不再自行补 `default` 租户。宿主应只提供
Access Token/Session，由服务端认证适配器写入可信上下文；旧网关确实需要租户 Header 时可显式
开启 `sendTenantHeader` 兼容模式，但网关必须用认证结果覆盖该值。这一设计保持了 Alibaba Studio
式组件嵌入体验，同时不引入其 Admin 身份模型。

对比 Alibaba Studio 的 Graph `StateInspector`、`NodeTimeline` 和 HITL 消息组件后，Agent Start
不复制它的 Next.js 工作台，而是在 Vue SDK 中强化统一的 `AgentRunTimeline`：Native 与 Alibaba
Runtime 共用 durable `/agent-runs` 协议，可查看租户、Agent、会话、版本、耗时、请求/定义/响应
快照和有序事件。组件提供事件过滤、选择通知、公开刷新/取消方法，以及 header、approval、event、
empty、actions slots，使企业宿主可以接入自己的 RBAC、审批卡片、审计详情和设计系统。

Native 与 Alibaba Runtime 的推理步骤不再只通过瞬时 SSE 发送。`THOUGHT / ACTION / OBSERVATION /
FINAL / APPROVAL / DELEGATION` 统一写入追加式 `AgentRunStore` 事件流，事件类型为 `STEP_*`；Token
增量仍只走流式通道，避免把数据库变成高频 Token 日志。Vue `AgentRunTimeline` 可以选择任意 durable
事件并在响应式状态检查器中查看当时负载，刷新后仍可回放；宿主可通过 `inspector` slot 替换检查器。
这比直接嵌入 Alibaba Studio 更符合本项目边界：执行数据协议可复用，身份、布局和权限仍由宿主掌握。

当前 Vue 全量类型检查还暴露出 Connector Hub 的 SDK 漂移：`MyRobotsPanel` 引用了尚未导出的
`MyRobot` 和 `client.robots`，并未向 `RobotFormModal` 提供必需的 channels/agents。该问题属于组件
契约健壮性缺口，发布嵌入式 SDK 前必须修复并加入 public-export/typecheck 门禁。
