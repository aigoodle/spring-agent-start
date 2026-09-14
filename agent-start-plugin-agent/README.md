# Plugin 与 Agent 的适配层

本模块属于平台框架，不包含视频生成业务。保留在根目录，与 `agent-start-plugin` 通用 SPI 分开。

| 能力 | 实现 |
|---|---|
| 将插件作为 Agent runtime 调用 | `PluginAgentRuntime`，runtimeType=`PLUGIN` |
| 让插件反向调用平台聊天模型 | `PluginModelCapability`，能力名 `model.chat` |
| 让插件调用平台已注册工具/MCP | `PluginToolCapability` |
| 为 Agent 提供插件 Skill 目录和按需读取 | `PluginSkillTools` |

只调用厂商视频 API 的插件依赖 `agent-start-plugin` 即可；需要以上平台能力时，宿主再引入本模块。Java 可信插件也可以自行依赖模型模块并注入 ModelService。

具体业务实现在 `agent-start-plugins/`：`agent-start-plugin-seedance` 负责生成视频，`agent-start-plugin-example` 演示商品资料查询与视频简报。前者可以接收上游 LLM 节点的脚本作为 prompt，无需把业务逻辑放进本适配层。
