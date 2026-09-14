# Plugin SPI、Connector、Agent 与 Skill

状态：插件扩展基础实现已落地；业务插件集中到 `agent-start-plugins`，已添加 Seedance 文生视频实现。详见 [插件集合开发与使用说明](../../agent-start-plugins/README.md)。

## 目标与分层

Plugin 是业务扩展包，可以包含 Actions、技能说明和 Agent 能力。JAVA 与 REMOTE_HTTP 是运行方式，不是两种业务概念。Connector 继续承担外部系统接入，并通过 `PluginConnectorProvider` 同时承载插件目录和调用。插件业务实现不必实现 ConnectorProvider。

- Plugin：开发者交付的业务能力、版本、配置协议与执行实现。
- Action / Tool：可调用操作。工作流显式调用，Agent 可以通过工具适配器选择调用。
- Agent runtime：有自主决策/多步执行需求时使用的执行入口；普通视频 API 不必包装成 Agent。
- Skill：告诉 Agent 何时、如何使用 Actions 的说明及辅助资源，不等同于执行接口。
- Connector：统一安装、连接、执行策略、审计及工作流/工具适配层。

## 官方资料及采用的机制

2026-09-11 查阅以下一手资料。这里参考设计，不声明兼容执行其他平台的插件包。

| 来源 | 参考机制 | 本项目落点 |
|---|---|---|
| [n8n 节点参数](https://github.com/n8n-io/n8n-docs/blob/main/docs/connect/create-nodes/build-your-node/reference/base-files/standard-parameters.md) | 节点描述、操作、字段与条件显示 | Manifest、Action inputSchema 和 uiSchema |
| [n8n 凭证文件](https://github.com/n8n-io/n8n-docs/blob/main/docs/connect/create-nodes/build-your-node/reference/credentials-files.md) | 连接认证与操作参数分离 | 租户安装与加密连接，节点仅保存 connectionId |
| [Dify Manifest](https://docs.dify.ai/en/develop-plugin/features-and-specs/plugin-types/plugin-info-by-manifest) | 扩展包描述、运行环境与权限 | Manifest 与部署配置分离、能力声明与管理员授权分离 |
| [Dify 反向调用](https://docs.dify.ai/en/develop-plugin/features-and-specs/advanced-development/reverse-invocation) | 插件调用平台工具和模型 | PluginHost / PluginHostCapability |
| [Agent Skills 规范](https://github.com/agentskills/agentskills/blob/main/docs/specification.mdx) | SKILL.md、资源目录、渐进加载 | 技能目录和按需激活；说明不赋予执行权限 |

## 当前代码

`agent-start-plugin` 提供：

- `Plugin.manifest()` 与 `Plugin.execute(invocation, context)`：Java Bean SPI。
- `PluginManifest`：ID、版本、配置表单、动作列表、所需宿主能力。
- `PluginContext`：执行身份、连接配置、凭证与 PluginHost。禁止单例缓存上下文。
- `PluginConnectorProvider`：统一投影成 provider=`plugin` 的 Connector。
- `StoredPluginConnectionResolver`：检查安装启用、租户、连接所属插件与配置状态。
- `RemotePlugin`：部署方配置地址和 Token，执行 HTTP v1 协议。
- `PluginHostFactory`：能力必须既被插件声明又被部署方授权；实现仍须检查具体数据权限。
- `agent-start-plugin-agent`：`PLUGIN` Agent runtime、`model.chat` 非流式能力、`PluginToolCapability`（含已注册 MCP 工具）、`plugin_skills_list/read`。
- `PluginSkills.load`：读取部署方打包的 SKILL.md YAML frontmatter 和显式参考资源，不执行技能脚本。
- `PluginHostController`：普通 HTTP POST `/agent-start/plugin-host/v1/models/chat` 与 `/capabilities/{name}`，MVC/WebFlux 通用，阻塞模型工作切换到 boundedElastic。

JAVA 是可信企业代码，与宿主同进程，能力授权不是 JVM 沙箱。需要进程/依赖隔离的 Python、Node.js 或 Java 服务部署在独立容器，使用相同远程协议。平台不负责启动 Docker，也不接受任意在线上传 JAR。

## HTTP v1

地址、服务令牌由管理员配置，不能从工作流输入覆盖。Manifest 从宿主 classpath/file 加载，启动不依赖远端可用性。

POST `/v1/execute` 请求包含：

```json
{
  "protocolVersion": "1",
  "pluginId": "acme.video",
  "pluginVersion": "1.0.0",
  "invocation": {"actionId": "prepare", "inputs": {"productId": "123"}},
  "identity": {"executionId": "e1", "tenantId": "t1", "userId": "u1"},
  "configuration": {},
  "credentials": {}
}
```

完成时返回：

```json
{"completed":{"result":{"success":true,"data":{"text":"准备完成"},"content":[],"metadata":{}}}}
```

需要查询平台数据时返回：

```json
{"hostCall":{"id":"c1","capability":"product.read","arguments":{"productId":"123"},"state":{"phase":"prepare"}}}
```

宿主以原始执行身份调用授权能力，再 POST `/v1/resume`，保留原请求并增加 `hostCallId`、`hostResult`、`state`。远端不能替换宿主身份。此请求/响应模式不需要宿主开放反向回调端口；单次调用最多 16 次宿主能力请求，HTTP 共享整体截止时间。宿主能力执行自身也应有超时控制。

也支持插件主动 HTTP 反向调用。设置 `host-base-url` 与 `host-signing-secret` 后，执行请求附带 `host: {baseUrl, token, expiresAt}`。插件 POST `${host.baseUrl}/models/chat`，携带 `Authorization: Bearer ${host.token}`。Token 包含签名的原始执行身份、插件版本、能力范围，最长有效 5 分钟；每次回调重新检查租户安装启用与管理员授权。插件不知道签名密钥，也不会收到模型厂商凭证。

```json
{
  "modelId": "tenant-owned-model-id",
  "messages": [{"role": "user", "content": "请编写商品视频脚本"}],
  "temperature": 0.7,
  "maxTokens": 1024,
  "stream": false
}
```

响应包含 `text`、`modelId`、`finishReason` 和可用时的 `usage`。选择的模型必须是签名租户下启用的 LLM。调用请求不接受租户、API Key 或模型服务地址覆盖。复用已有 ModelService/ChatModel，并返回模型响应的用量信息；不在插件模块另建计费账本。模型自身连接超时由原模型配置负责。

内部 Java 插件既可使用 `context.host().call("model.chat", arguments)`，也可在自身 Maven 模块依赖 `agent-start-model`，通过构造器注入 ModelService 后调用 `getChatClient(context.identity().tenantId(), modelId).prompt().user(prompt).call().content()`。后者属于可信同进程扩展，不具有跨进程能力隔离。

`state` 是插件拥有的 JSON 状态，不应存放宿主凭证。返回状态必须二选一，完成与 hostCall 不能同时出现。HTTP 失败不自动重试，防止不确定的提交结果造成重复副作用。

## 配置

```yaml
spring-agent:
  plugin:
    host-base-url: https://platform.example/agent-start/plugin-host/v1
    host-signing-secret: ${PLUGIN_HOST_SIGNING_SECRET}
    grants:
      "[acme.video]": [product.read, model.chat]
    remotes:
      - manifest: file:/etc/agent-start/plugins/video.json
        endpoint: http://video-plugin:8091/
        token: ${VIDEO_PLUGIN_SERVICE_TOKEN}
        timeout: 60s
```

租户通过已有 Connector 安装接口同步目录、启用安装、保存连接。没有连接的插件可以不传 connectionId，但仍需启用的租户安装。业务凭证只下发到管理员指定的插件服务，跨主机部署应使用 TLS。

## 完成验收清单

- [x] Java 插件 SPI、自动装配与重复 ID 检查。
- [x] Connector 目录/网关适配与配置解析入口。
- [x] HTTP v1 执行和宿主能力往返协议。
- [x] 独立 Java 示例模块与 Python 项目；Python 跨进程 HTTP 模型反向调用集成测试通过。
- [ ] Docker 镜像构建/运行验证（本机未发现 Docker；已提供 Dockerfile 与 compose）。
- [x] 内部 HTTP、MCP 工具与 Agent 能力适配代码；模型与 Agent 身份传播测试通过。
- [x] Skill 资源加载、租户安装过滤与 Agent 按需读取工具；示例 Skill 格式验证通过。
- [x] Vue 表单 uiSchema、动作输出变量树、插件运行方式展示及 Agent runtime 选择；相关测试与类型检查通过。
- [x] 串行工作流任务检查点、查询、取消/超时通知及更换 Runner 实例恢复测试。
- [x] 宿主模型 HTTP 路由自动装配、鉴权、已有工作流/Connector 回归验证；server/example Maven reactor 测试通过。

`AgentNodeExecutor` 已支持 `runtimeType=PLUGIN` 与 `runtimeRef=pluginId/actionId`，无需填写内置 Agent 的模型配置。动作必须显式声明 `metadata.agentRuntime=true`。Agent runtime 的生命周期恢复暂未在 Plugin 适配器实现；普通同步动作可使用该入口，长视频任务仍需下方任务协议。

## 长任务边界

Java 长任务实现 `AsyncPlugin` 的 execute/query/cancel。动作必须声明 `metadata.executionMode=ASYNC` 且 `idempotent=true`；平台在加载时拒绝不满足条件的声明。插件必须实际按 `(tenantId, executionId, actionId)` 去重提交，并持久化外部任务；声明本身不会替插件实现去重。

HTTP 插件受理任务后返回：

```json
{"completed":{"task":{"id":"vendor-job-123","state":{},"nextPollAt":"2026-09-11T04:00:00Z"}}}
```

`completed` 表示这次 RPC 已返回，内含 task 时业务仍为 PENDING。后续宿主 POST `/v1/tasks/query` 或 `/v1/tasks/cancel`，请求保留原身份、动作参数并增加同一 `task`；query 返回新的 task 或完成结果。cancel 必须幂等，可返回 `{"completed":{"result":{"success":true,"data":{"cancelled":true},"content":[],"metadata":{}}}}`。插件服务必须校验任务属于当前租户和执行，不能只凭外部任务 ID 访问。

Connector 工作流节点将任务、插件版本、原始请求、截止时间写入现有 variablePool 检查点，返回 SLEEP_UNTIL 等待。定时恢复服务唤醒后执行 query，不重新提交；下游仅在完成后取得 result.data。插件版本变化时拒绝继续查询，部署需保留在途任务所用版本。`taskTimeoutSeconds` 默认 900，允许 1～86400。每次远程查询都会获得新的短期宿主调用凭证，后台任务不能无限复用旧 Token。

已测试单个串行等待任务在更换 Runner 实例后的恢复。当前引擎使用单一等待入口，尚不承诺并行分支多个异步等待或嵌套 ITERATION 子流程的持久化恢复。取消/超时先保存工作流终态，再尽力通知外部任务；网络故障或此时崩溃不能保证外部停止，当前没有独立取消 Outbox，插件应提供任务 TTL 和运维清理能力。

Agent runtime / Tool 收到 task 会明确返回 PENDING 描述，不代表视频完成；AgentRun 自动恢复尚未实现。需要等待最终视频并继续下游时使用 Connector 工作流节点。随附 Java/Python 示例生成商品视频脚本，不调用真实视频厂商或生成视频文件。

## 开发者入口与表单

Java 项目依赖 `agent-start-plugin`，将 `Plugin` 实现注册为 Spring Bean；独立 Maven starter 可使用 AutoConfiguration.imports，参考 `agent-start-plugins/agent-start-plugin-example`。需要模型、Agent 或 MCP 工具适配时增加 `agent-start-plugin-agent`。Python 的可运行协议示例位于 `examples/plugins/python-video`；Node.js 服务实现相同 HTTP 协议即可，当前未附 Node.js SDK。

插件描述推荐独立 YAML 文件，通过 `PluginManifests.load(Resource)` 读取；Schema 可直接写 YAML 对象，加载后转换为现有 JSON Schema 字符串。旧 JSON 格式仍兼容。`skills[].path` 引用相对目录内的 SKILL.md，表单配置和技能说明各自维护。

配置 Schema 描述连接配置；动作 inputSchema 描述每次调用参数；outputSchema 描述下游变量。uiSchema 只控制渲染，校验仍使用 JSON Schema 2020-12（仅本地引用）。例如动作 metadata：

```json
{
  "uiSchema": {
    "order": ["modelId", "prompt"],
    "fields": {
      "modelId": {"widget": "input", "placeholder": "平台模型 ID"},
      "prompt": {"widget": "textarea", "allowVariable": true}
    }
  }
}
```

前端还支持 showIf 条件显示；配置字段 `writeOnly=true` 或 `format=password` 存入凭证区。uiSchema 不支持执行 JavaScript，Schema 的 default 不会由后端自动填入。新增字段须明确兼容已有安装和已保存工作流。

## 本机测试备注

Windows 当前 Oracle JDK 的 selector 唤醒管道使用 Unix domain socket 时出现 Invalid argument。将 `jdk.net.unixdomain.tmpdir` 指向不存在的路径可使 JDK 回退到 TCP 回环，HTTP 集成测试仍使用真实网络，不跳过测试：

```powershell
mvn -pl agent-start-plugin -am test '-DargLine=-Djdk.net.unixdomain.tmpdir=C:/tmp/plugin-socket-path-not-present'
```

这是本机验证参数，不应作为插件运行库的全局系统属性写入。

最终后端验证命令为 `mvn -pl agent-start-server,agent-start-example,:agent-start-plugin-example -am test`，附上述 JVM 参数与 `-Dplugin.python=<本机 Python 路径>`。涵盖真实独立 Python 进程的模型回调、HTTP 任务协议、检查点恢复、取消/超时、Spring 宿主路由装配；模型响应使用测试替身，不消耗真实模型或视频服务额度。已有需外部环境的可选测试保持其原有跳过条件。

Vue 侧运行了 Connector/工作流 Vitest 与 vue-tsc。另修正 AppDesignDrawer 发布事件携带完整保存参数，避免原有类型错误及保存/发布同时触发的竞争。Docker 构建与真实视频厂商联调未执行。
