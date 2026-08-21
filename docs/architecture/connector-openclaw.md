# Connector 与 OpenClaw 集成架构

## 结论

先建设自有 Connector 底座是必要的，但底座不等于重写 QQ、邮件、飞书等插件。
本项目拥有统一的目录、连接、权限、执行和审计语义；OpenClaw 负责运行其生态插件。
这样工作流和 Agent 只依赖稳定的 Java SPI，外部生态可以替换或并存。

```text
管理 UI / Workflow / Agent
          |
ConnectorRegistry + ConnectorExecutionGateway
          |
ConnectorProvider SPI
          |
OpenClaw Java Provider -- authenticated HTTP --> Agent Start Bridge
                                                  |
                                      OpenClaw tools.catalog/tools.invoke
                                                  |
                                      ClawHub / OpenClaw plugins
```

## 模块

- `agent-start-connector`：供应商无关的 Connector 定义、注册中心、执行策略、加密连接存储。
- `agent-start-connector-redis`：可选的 Redis 集群出站限流；额度按租户、Provider、Runtime 节点和账号隔离。
- `agent-start-connector-tools`：把 Connector action 动态映射为 Agent 可调用工具。
- `agent-start-connector-openclaw`：Bridge HTTP 客户端和 OpenClaw Provider。
- `openclaw/agent-start-bridge`：安装在 OpenClaw Gateway 内的受认证桥接插件。
- `agent-start-workflow`：`CONNECTOR` 节点直接调用统一执行网关。
- `agent-start-web`：目录、连接和 OpenClaw 插件生命周期管理 API。

## 管理 API

所有 MVC 管理接口最终位于 `/agent-start` 前缀下：

- `GET /agent-start/connectors`
- `POST /agent-start/connectors/refresh`
- `POST /agent-start/connectors/{provider}/{connectorId}/actions/{actionId}/execute`
- `GET|POST|DELETE /agent-start/connector-connections`
- `POST /agent-start/connector-connections/{id}/test`（验证租户归属及加密配置可读性）
- `GET /agent-start/connector-executions`（租户级执行审计，支持 provider/status 过滤）
- `GET /agent-start/connector-installations`
- `POST /agent-start/connector-installations/synchronize`
- `POST /agent-start/connector-installations/{id}/enable|disable`
- `GET /agent-start/openclaw/runtime|plugins|tools`
- `POST /agent-start/openclaw/plugins/install`
- `PUT /agent-start/openclaw/plugins/{id}/config`
- `POST /agent-start/openclaw/plugins/{id}/enable|disable`
- `DELETE /agent-start/openclaw/plugins/{id}`

## 安全边界

- Bridge 仅建议暴露在内部网络，并强制 `X-Agent-Start-Token`。
- 插件安装默认关闭。私有化部署方可通过 `OPENCLAW_ALLOW_INSTALL=true` 开启任意合法
  npm 包名或 ClawHub 标识符的安装，用于部署自研插件；该生命周期 API 属于平台级管理能力，
  必须由宿主系统或 API 网关限制为部署管理员使用。
- Bridge 不接受 shell 命令、文件路径或任意 CLI 参数，且只应位于内部网络；开放插件来源
  不等于开放命令执行入口。npm 插件本身仍是部署方信任并执行的第三方代码。
- Connector 凭据使用租户派生的 AES-GCM 密钥加密入库；跨租户解密会失败，并兼容滚动升级期间的
  旧密文。宿主可注入 `TenantTextEncryptor` 对接 KMS/HSM。列表 API 只返回是否已配置，不回传密文或明文。
- OpenClaw 不可达时 Java 主服务仍可启动，并保留最后一次成功发现的目录快照。
- 高风险 action 通过 `ConnectorExecutionPolicy` 扩展点接入审批、租户配额和审计策略。
- 租户账号必须通过 `/channel-connections` 管理。底层 `/channels/{provider}/{channelId}/accounts`
  面向共享 Runtime 节点，可能包含多个租户的账号，默认仅接受 `PLATFORM_ADMIN`、`SYSTEM_ADMIN`
  或 `DEPLOYMENT_ADMIN`；宿主可通过 `ChannelRuntimeAdministrationPolicy` 对接自己的部署级权限。
  `TENANT_ADMIN` 和 `CONNECTOR_ADMIN` 不能枚举或修改共享 Runtime 账号。

连接控制面使用可恢复 Saga：创建时先持久化带全局 `runtime_account_id` 的 `PENDING` 行，再调用
Runtime。后台 `ChannelConnectionReconcileWorker` 通过数据库租约竞争 PENDING/ERROR 连接，按
指数退避重新应用期望配置；OpenClaw/Hermes 的保存操作必须以稳定 account id 幂等。可通过
`spring-agent.connector.channel-connection-reconcile-*` 调整开关、轮询、租约和批大小。

## 出站富消息

Java Runtime 到 Bridge 会保留 `messageType`、附件和结构化展示数据。Bridge 将附件 URL
映射到 OpenClaw 的 `--media` 传输；多个附件作为同一个幂等投递组依次发送，正文和
presentation 只附加在第一条投递上，整个投递组的结果统一写入幂等存储。

附件 URL 最终交给已安装的 OpenClaw 渠道适配器读取。生产宿主应在消息入队前实现 URL
白名单、对象存储有效期和恶意文件扫描策略。

Agent 自动回复与人工回复使用同一 Outbox。运行时入站回调只返回“已接管/回复已入队”，不会把
回复文本交给 OpenClaw 立即发送；入站事件和对应的 `PENDING` Agent 出站事件在同一数据库
事务内创建。持久化失败会使回调失败，以便运行时重试，而不是确认后丢失回复。重复入站只返回
无文本确认，避免 OpenClaw 在重放回调时绕过幂等队列重复发送。

入站事件同时保存发送者 `sender_id` 和平台回复目标 `reply_target_id`，两者不能混用。私聊的
回复目标通常是发送者；群聊/频道的回复目标必须是群或频道会话标识。Outbox 只向
`reply_target_id` 投递，历史数据为空时才兼容回退到 `sender_id`。Bridge 必须从平台原始上下文
写入回复目标，不能由 Java 后端根据 ID 格式猜测。新增适配器至少需要通过“群成员发送消息，
回复仍进入原群而非成员私聊”的契约测试；真实平台 E2E 未通过前不得声明群聊能力可用。

正式回调必须提供平台 `messageId`。Java 后端在执行 Agent 前先插入 `PROCESSING` 入站事件并
取得数据库租约；并发重投返回 `503` 让 Bridge 延迟重试，不会再次调用 Agent。若应用节点在
执行中退出，租约到期后其他节点可原子接管。租约默认 2 分钟，可通过
`spring-agent.connector.channel-inbound-lease-duration` 调整。Agent 执行期间后台每隔约三分之一
租期续租；短暂数据库故障不会永久停止后续心跳，完成或异常退出时会取消续租。

多实例部署应引入 `agent-start-connector-redis`。存在 `StringRedisTemplate` 时该模块默认用 Lua
脚本原子执行 `INCR + PEXPIRE`，所有应用节点共享同一账号每秒额度；Redis 故障时限流失败关闭，
Outbox 保持待发送而不会绕过额度。可用
`spring-agent.connector.channel-rate-limit.backend=memory` 显式恢复单 JVM 模式，并通过
`spring-agent.connector.channel-rate-limit.key-prefix` 设置独立部署前缀。

## 当前兼容边界

兼容基线为 OpenClaw `2026.7.1-2`。该版本公开的 `tools.catalog` 没有提供每个工具的
完整可执行 JSON Schema，因此目录中使用开放对象 Schema，最终参数校验由
`tools.invoke` 负责。后续 OpenClaw 若公开 Schema，可在 Bridge 内增强，无需改变工作流定义。

## Docker 联合启动

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.openclaw.yml up --build
```

生产环境必须设置不同的 `OPENCLAW_GATEWAY_TOKEN`、`OPENCLAW_SERVICE_TOKEN` 和
`CONNECTOR_SECRET`。OpenClaw 状态由 `openclaw-data` 保存；Backend 只通过内部 HTTP
访问 Bridge。Bridge 通过 OpenClaw 官方 CLI 的 Gateway RPC 调用目录和工具，这是因为
`2026.7.1-2` 限制非官方第三方插件直接使用进程内 `runtime.gateway.request`。

## QQBot 黑盒 E2E

`QQBotBlackBoxE2ETest` 验证后端回复 API、数据库 Outbox、OpenClaw、QQ 网络、入站回调和
消息时间线的完整链路。测试对端需配置为将 `AGENT_START_E2E:` 开头的随机标记原样回复：

```bash
QQBOT_E2E_ENABLED=true \
QQBOT_E2E_SOURCE_EVENT_ID=<一条属于测试账号的入站事件ID> \
QQBOT_E2E_BACKEND_URL=http://127.0.0.1:18090/agent-start \
QQBOT_E2E_TIMEOUT_SECONDS=90 \
mvn -pl agent-start-web -am \
  -Dtest=QQBotBlackBoxE2ETest -Dsurefire.failIfNoSpecifiedTests=false test
```

如宿主接口需要认证，通过 `QQBOT_E2E_HEADERS_JSON` 传入请求头 JSON。测试默认关闭，因此普通
构建不会发送真实 QQ 消息。仅有 Bridge 健康或直接发送成功不算该测试通过；必须观察到带平台
消息 ID 的 `SENT/DELIVERED` 出站事件和携带同一随机标记的新入站事件。
测试在回复入队后取得服务端返回的 `conversationId`，后续只轮询该会话的游标时间线；即使 200
个账号同时产生消息，目标事件也不会因掉出租户全局最近 200 条记录而被误判为超时。

## 连接容量验收

`ChannelConnectionCapacityAcceptanceTest` 面向已经配置好的真实账号池，不创建账号也不发送消息。
它要求当前租户至少存在指定数量的活动连接，并并发执行多轮连接探测，校验成功率和 p95 延迟。
探测会更新账号最近测试状态并写入审计记录，因此必须显式启用：

```bash
CHANNEL_CAPACITY_ENABLED=true \
CHANNEL_CAPACITY_EXPECTED_CONNECTIONS=200 \
CHANNEL_CAPACITY_CONCURRENCY=32 \
CHANNEL_CAPACITY_ROUNDS=3 \
CHANNEL_CAPACITY_MIN_SUCCESS_RATE=0.99 \
CHANNEL_CAPACITY_MAX_P95_MS=5000 \
CHANNEL_CAPACITY_HEADERS_JSON='<宿主认证请求头JSON>' \
mvn -pl agent-start-web -am \
  -Dtest=ChannelConnectionCapacityAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

该验收证明 200 个已配置连接能被平台并发管理，但不能替代 24 小时稳定性测试。正式容量结论还应
同步采集 OpenClaw 容器的 CPU、RSS、文件描述符、重连次数和 QQ 限流/断线率。

真实环境的验收记录应保存在 `docs/verification/`，并明确区分出站成功、平台投递回执、入站回显
和容量/稳定性结论。最近一次记录见
[`channel-acceptance-2026-08-20.md`](../verification/channel-acceptance-2026-08-20.md)。

Hermes 的 `docker-compose.hermes.yml` 是基础 Compose 的 overlay，必须与
`docker-compose.yml` 一起使用。Bridge 提供带 Token 的健康检查，由 Docker init 监管 Python
子进程；后端只在 Dashboard 与 Bridge 均健康后启动。Profile 列表为空只证明基础设施可达，不代表
任何消息账号已接入，也不能将 `agentStartInbound/agentStartOutbound` 能力标记为可用。

每个 Hermes QQ Profile 的健康状态还区分传输连接和 Agent Start 入站回调任务：
`connected` 只表示 QQ 传输在线，`callbackWorkerRunning`、`callbackWorkerFailures` 和
`callbackWorkerError` 表示持久化回调队列是否仍在工作。回调任务遇到临时 SQLite/文件系统异常时
会指数退避并自恢复，不能因为 QQ 仍在线而隐藏消息无法进入 Agent Start 的故障。

连接健康快照由后端低频分批刷新并持久化到 `runtime_metadata_json`，管理页面只读取快照，避免
200 个账号的每次页面刷新形成 Runtime N+1 探测。多节点通过数据库租约确保同一连接只有一个节点
执行探测。默认每 30 秒扫描一批、健康快照 2 分钟后过期、每批 20 个，可通过
`spring-agent.connector.channel-connection-health-enabled`、`channel-connection-health-poll-interval`、
`channel-connection-health-refresh-interval`、`channel-connection-health-lease-duration` 和
`channel-connection-health-batch-size` 调整。探测异常标记为 `DEGRADED`，不会立即重建仍可能在线的账号。
