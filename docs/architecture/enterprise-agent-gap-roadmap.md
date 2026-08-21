# Agent Start 企业级能力对标路线

对标对象是本地 Spring AI Alibaba 源码，而不是其 Admin 页面。目标是吸收成熟的产品闭环和设计模式，
继续保持 Agent Start 的嵌入式 SDK、宿主身份体系和 Native Runtime 自主性。

## 差距矩阵

| 领域 | Alibaba 的成熟设计 | Agent Start 现状 | 决策与优先级 |
|---|---|---|---|
| 执行治理 | Model/Tool Call Limit、超时、重试、Fallback | 已有超时、取消、迭代上限、Tool Gateway | P0：补模型/工具预算；随后补模型重试与降级策略 |
| 上下文工程 | Summarization、Context Editing、消息更新策略 | 已有上下文窗口、分层记忆、抽取式压缩 | P0：补 Token 预算、压缩原因和压缩前后审计 |
| HITL | Hook、中断、反馈恢复 | 已有持久化暂停/恢复和批量审批协议 | P0：补审批超时、升级、处理人和 SLA |
| 多 Agent | Sequential、Parallel、Routing、Loop、A2A | 已有 delegation tool 和三种单 Agent Strategy | P1：提供组合 Agent 定义、聚合策略和失败语义 |
| 状态与恢复 | Graph State、Checkpoint Saver、多存储 | AgentRunStore、事件流、ReAct Checkpoint | P1：统一 Native Checkpoint，覆盖 Workflow 和所有 Strategy |
| 扩展机制 | Hook + Model/Tool Interceptor | Spring Bean SPI、Runtime/Tool Interceptor、Model Decorator | P1：统一 ordered policy 元数据和决策审计 |
| 可观测性 | Graph Observation、节点/模型/工具事件 | LLMOps、AgentRun 事件、渠道指标 | P1：补 trace/span 关联、预算消耗和失败分类 |
| 安全 | PII Hook、Tool Selection、Shell/Filesystem 隔离 | 租户隔离、可信上下文、Tool Policy | P1：补 PII 检测/脱敏 SPI、工具风险分级和数据出境策略 |
| 产品生命周期 | Studio 调试与 Admin 发布 | 草稿、发布快照、回滚、运行时间线 | P2：补灰度发布、版本对比、评测门禁和快速回滚指标 |
| 企业运维 | 多存储、远程 Agent、生态配置 | Starter、Connector Control Plane | P2：补健康诊断、容量档位、兼容矩阵和升级报告 |

## 已落地：执行预算

每个 Agent 的模型配置 sidecar 新增 `maxModelCalls` 和 `maxToolCalls`。零值保持向后兼容，表示不增加
预算限制；正值在实际模型请求或工具执行之前原子声明配额，超限失败关闭，确保并发工具调用不会穿透
限制。ReAct、Plan-Execute 和 Function Calling 共用同一 `AgentRunContext` 预算协议。

数据库升级使用 `V2_4__agent_execution_budgets.sql`。宿主编辑器可以直接提交这两个字段；后续 UI 应将其
放在“运行保护/成本控制”区域，并明确它们与 `maxIterations` 的区别。

## 产品原则

1. 每个高级能力必须形成配置、执行、持久化、观测、故障恢复和测试六个闭环。
2. 默认值保持安全且向后兼容；涉及跨租户、审批和外部写操作时失败关闭。
3. 核心协议不绑定第三方框架，外部生态通过 SPI 或 Connector 接入。
4. Admin UI 不是能力本身；所有核心功能先有稳定 API/SDK，再由嵌入式组件呈现。
5. 发布功能必须附带升级脚本、兼容说明、指标和可重复验收测试。
