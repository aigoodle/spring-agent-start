# Hermes runtime integration

Agent Start treats Hermes as a **runtime provider**, not as a second connector catalog. A user-facing
message platform (for example QQ) is the stable product concept; `openclaw` and `hermes` are alternative
runtime adapters beneath it.

## Enable

```yaml
spring-agent:
  connector:
    hermes:
      enabled: true
      base-url: http://127.0.0.1:9119
      api-token: ${HERMES_API_TOKEN:}
      timeout: 15s
```

The base URL is the Hermes Dashboard/Gateway management service, not Hermes' separate API server.
Each Agent Start channel connection maps to one Hermes `profile`, which supplies the isolation boundary
for an employee or service account. Tenant and employee ownership remain in Agent Start.

## Docker Compose

The repository provides `docker/docker-compose.hermes.yml` as an overlay for the normal demo stack:

```bash
cd docker
docker compose -f docker-compose.yml -f docker-compose.hermes.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.hermes.yml ps
docker compose -f docker-compose.yml -f docker-compose.hermes.yml logs -f hermes
```

It runs the official `nousresearch/hermes-agent` image in `gateway run` mode, starts the supervised
Dashboard on port 9119 and automatically configures the Agent Start backend to use
`http://hermes:9120`. State is persisted in the `hermes-data` volume. The Dashboard itself remains
bound to container loopback on 9119, while a small Nginx sidecar sharing the Hermes network namespace
forwards 9120 only to the Compose private network and normalizes the trusted Host header. Host access binds only to `127.0.0.1`; do not expose
the Dashboard management surface directly to a LAN or the internet.

At least one model-provider credential must be supplied before Hermes can answer messages, for example:

```bash
export OPENROUTER_API_KEY=your-key
docker compose -f docker-compose.yml -f docker-compose.hermes.yml up -d --build
```

For an interactive first-time Hermes setup instead, run:

```bash
docker compose -f docker-compose.yml -f docker-compose.hermes.yml run --rm hermes setup
```

## Agent Start bridge

The Compose overlay installs the opt-in `agent-start-bridge` Hermes platform plugin and a separate
`hermes-agent-start-bridge` router. The first supported wrapped transport is Hermes' official QQBot
adapter; its WebSocket, reconnect and native send implementation remain owned by Hermes.

QQ 入站事件先写入 Profile 本地 SQLite callback Outbox，再调用 Agent Start。后端短暂不可用时
插件按指数退避重试，Profile 进程或容器重启后继续投递；幂等范围为
`profile + platform + messageId`，Java 端仍以平台消息 ID 做最终去重。因此入站可靠性不依赖
QQ WebSocket 是否重放已接收事件。Profile 健康结果同时暴露 `pendingInboundCallbacks`。

Router 的 `/health` 不以 Unix Socket 文件存在作为在线依据，而会主动请求每个 Profile Adapter 的
健康端点，返回 `profiles`、`connectedProfiles` 和 `profileStates`。因此异常退出留下的 socket 会被
标记为 `reachable=false`，不会误报为已连接；WebSocket 重连仍由 Hermes 官方 Adapter 负责。
Java `HermesChannelRuntimeProvider.accounts(channelId)` 使用同一份认证快照发现运行时账号，并按
`platform` 过滤，映射 `running=reachable`、`connected=connected` 以及
`pendingInboundCallbacks`。因此部署管理员看到的是 Profile Adapter 的实际状态，而不是数据库
期望状态；租户自己的连接列表仍以 `agent_channel_connection` 为权威来源，不会借此枚举其他租户。

## QQBot 黑盒 E2E

`HermesBlackBoxE2ETest` 与 OpenClaw 使用相同的企业验收口径：测试从一条属于 Hermes
连接的真实入站事件发起回复，要求对端将随机标记原样发回。只有同时观察到带平台消息 ID 的
`SENT/DELIVERED` 出站事件，以及携带相同标记、消息 ID 和会话 ID 的 Hermes 入站事件才算通过。

```bash
HERMES_E2E_ENABLED=true \
HERMES_E2E_SOURCE_EVENT_ID=<一条属于Hermes测试账号的入站事件ID> \
HERMES_E2E_BACKEND_URL=http://127.0.0.1:18090/agent-start \
HERMES_E2E_TIMEOUT_SECONDS=90 \
mvn -pl agent-start-web -am \
  -Dtest=HermesBlackBoxE2ETest -Dsurefire.failIfNoSpecifiedTests=false test
```

验收从回复事件读取可信 `conversationId` 并只轮询对应会话时间线，避免高并发租户中目标消息被
其他账号产生的事件挤出全局查询窗口。

如宿主接口需要认证，通过 `HERMES_E2E_HEADERS_JSON` 传入请求头 JSON。该测试默认关闭，普通
构建不会向真实 QQ 用户发送消息。Bridge 健康、Profile 在线或直接发送成功均不能替代此测试。

Inbound QQ events are normalized and posted to `/agent-start/channel-events/hermes` with a dedicated
shared token. Agent Start resolves `provider + profile + platform` to its channel connection and then
uses the normal connection/employee/tenant Agent routing policy. Replies enter the same durable Outbox
as every other provider.

Each Hermes profile gateway exposes a private Unix socket under `/opt/data/agent-start-bridge/`. The
single TCP router on port 9121 selects that socket by profile and invokes the live adapter. This avoids
port conflicts when many employee profiles run as separate gateway processes. The router persists
idempotency results in `agent_start_bridge.sqlite3`, so an Outbox retry after restart does not resend a
previously acknowledged message.

Hermes 入站回调同样不会直接返回可发送文本。Agent 回复与入站事件原子写入数据库后由统一
Outbox 投递；数据库或后端不可用时 Profile callback Outbox 持久化并重试，从而与 OpenClaw
保持相同可靠性语义。
Hermes 正式回调同样必须提供平台 `messageId`，并在 Agent 执行前竞争数据库入站租约；并发重投
不会重复执行 Agent，节点异常后可在 `spring-agent.connector.channel-inbound-lease-duration`
到期时由其他节点接管；正常的长耗时 Agent 调用会自动心跳续租。

Hermes Profile 也属于共享 Runtime 资源。租户管理员通过 `/channel-connections` 管理本租户连接；
直接枚举、保存、测试或删除 Runtime Profile 需要部署管理员权限，避免同一 Hermes 节点上的
跨租户账号暴露。

Bridge 插件源码位于 `docker/hermes/agent-start-bridge` 并由 Compose 只读挂载；数据卷只保存
Hermes 配置、Unix Socket 与 SQLite 幂等账本。新服务器不需要先从开发机复制
`docker/volumes/hermes/plugins`。出站幂等账本按 `platform + profile + idempotencyKey` 隔离，
所以不同租户 Profile 可以安全使用相同的租户内业务幂等键。

Profile 名称不是租户输入项。创建连接时 Java 控制面预生成全局唯一 connection id，并在调用
Hermes 前将同一个值持久化为 `runtime_account_id`；Hermes Provider 只接受这个可信 ID 作为
Profile。请求配置中伪造的 `profile` 会被忽略，避免两个租户选择同名 Profile 后覆盖凭据。
如果外部 Runtime 调用期间宕机，数据库保留可对账的 `PENDING` 连接，而不会留下无归属账号。
后台连接 Reconciler 会以数据库租约接管 PENDING/ERROR 行，并以指数退避重新应用 Profile 配置，
因此恢复不依赖用户再次点击保存。

Capability flags switch to `routingMode=AGENT_START` only while the authenticated router health check
succeeds. `runtimeStatus=OFFLINE` still means the individual Hermes platform/profile lacks credentials
or is disconnected; bridge availability must not be confused with account connectivity.

Current verified scope is QQBot text and native image/audio/video/document dispatch. Other Hermes
platforms remain `HERMES_NATIVE` until they receive an equivalent wrapper and provider contract test.

Real-environment verification is opt-in and never sends a message with implicit/demo targets:

```bash
HERMES_LIVE_TEST=true \
HERMES_BRIDGE_TOKEN=... \
HERMES_LIVE_PROFILE=employee-7 \
HERMES_LIVE_TARGET=qq-user-id \
mvn -pl agent-start-connector-hermes -Dtest=HermesLiveIntegrationTest test
```

Without `HERMES_LIVE_PROFILE` and `HERMES_LIVE_TARGET`, only the Dashboard and Bridge health smoke test
runs. This deliberate gate prevents normal CI from sending messages to real users.

Production deployments must replace `HERMES_BRIDGE_TOKEN`, keep port 9121 private, and expose the
Agent Start callback only to trusted runtime networks. Native fallback is fail-closed by default;
`AGENT_START_BRIDGE_FALLBACK_NATIVE=true` is an explicit availability-over-governance choice.
