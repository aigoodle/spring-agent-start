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
- 插件安装默认关闭；开启后仍按 npm/ClawHub source prefix 白名单校验。
- Connector 凭据使用 AES-GCM 加密入库，列表 API 只返回是否已配置，不回传密文或明文。
- OpenClaw 不可达时 Java 主服务仍可启动，并保留最后一次成功发现的目录快照。
- 高风险 action 通过 `ConnectorExecutionPolicy` 扩展点接入审批、租户配额和审计策略。

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
