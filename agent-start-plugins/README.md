# 业务插件集合

`agent-start-plugins` 是 Maven 聚合模块（packaging=pom），集中存放具体业务插件。宿主按需依赖子模块，不依赖整个集合。

```text
agent-start-plugin/                         通用 SPI、Manifest 加载、Java/HTTP 协议
agent-start-plugin-agent/                   Agent、平台模型反向调用、MCP/Skill 适配
agent-start-plugins/                        具体业务插件集合
  agent-start-plugin-example/               商品视频简报教学示例
  agent-start-plugin-video/                 通用视频生成，调用模型管理和 ModelProvider
  agent-start-plugin-seedance/              旧版独立 Seedance 插件（兼容模块）
```

`agent-start-plugin-agent` 是框架适配层，不是视频插件，不放入业务插件集合。

## 开发约定

每个插件提供 Java 业务实现和独立的 `src/main/resources/plugins/<名称>/manifest.yaml`。
Manifest 包含插件身份、配置 Schema、动作输入/输出 Schema、uiSchema 和权限声明。Schema 直接写 YAML 对象，无需把 JSON 转义塞进 Java 字符串。

`SKILL.md` 是可选的 Agent 使用说明，保持 Markdown + YAML frontmatter，不承担表单配置。在 manifest 的 `skills[].path` 引用，相对 manifest 所在目录解析。资源放在插件专属目录中，避免多个插件 JAR 文件重名。

`PluginManifests.load(Resource)` 同时兼容 YAML 和旧 JSON（包括字符串 Schema）；远程插件的 `manifest: file:/.../manifest.yaml` 也使用同一加载器。前端仍接收原有 JSON Schema。`timeout` 使用 ISO-8601，例如 `PT30S`。

## 通用视频节点

server 和 example 默认引入 `agent-start-plugin-video`，同时引入 `agent-start-provider-volcengine` 和 `agent-start-provider-qwen`。
模型协议属于 provider 模块，插件负责持久化任务，工作流负责等待和继续执行。

1. 在模型管理配置火山或 Qwen 提供商凭证，并注册、启用 VIDEO 类型模型。火山的视频模型名使用实际视频模型 ID 或视频 ep 接入点，不能使用聊天接入点。Qwen 适配支持 `wan2.6-t2v`、`wan2.6-t2v-us`。
2. 在插件管理同步并安装、启用 `media.video`（视频生成）。插件不再要求填写一份 API Key。
3. 工作流添加“视频生成”节点，在模型选择器中选择 VIDEO 模型，填写提示词和当前模型提供的参数。
4. 视频完成后从 `result.data.videoUrl` 引用地址；`result.data.taskId` 是平台任务 ID。

切换模型会清除旧模型参数并重新加载 Schema。聊天模型不能进入视频调用。默认模型配置也支持 VIDEO。
插件表单 Schema 保存在 `agent-start-plugin-video/src/main/resources/plugins/video/manifest.yaml`，模型专属参数 Schema 由 provider 返回。

部署、迁移和目前的发布限制见 [视频运行说明](agent-start-plugin-video/README.md)。

## 旧 Seedance 插件兼容使用

当前 server 和 example 已不再默认引入此模块。已有 `volcengine.seedance` 工作流需要继续保留以下依赖，等进行中的任务完成后再迁移；不要直接修改旧任务 ID：

```xml
<dependency>
  <groupId>io.github.aigoodle</groupId>
  <artifactId>agent-start-plugin-seedance</artifactId>
  <version>0.1.0</version>
</dependency>
```

1. 启动宿主，在 Connector 目录同步/安装并启用 **Seedance 视频生成**（`volcengine.seedance`）。
2. 创建连接，填写已开通的方舟视频模型/ep 接入点 ID 和 API Key。API Key 存入现有加密凭证区。
3. 工作流加入 Connector 节点，选择 provider=`plugin`、插件=`volcengine.seedance`、动作=`generate` 和连接。
4. 输入 `prompt`，可引用上游平台 LLM 节点生成的脚本；可选 duration、ratio、resolution 等参数。具体支持范围由所选视频模型校验。
5. 工作流等待后得到 `result.data.videoUrl`、`taskId` 和 `status`。需要长期保留时交给下游存储节点，插件不下载媒体。

默认方舟地址为 `https://ark.cn-beijing.volces.com/api/v3`。仅部署配置可覆盖 `spring-agent.plugins.seedance.base-url`，工作流输入不能覆盖地址。

插件需要宿主 DataSource。默认启动时执行 `db/plugin-seedance-schema.sql` 创建专属提交记录表；数据库迁移由运维管理时，先执行该 SQL，再设置 `spring-agent.plugins.seedance.initialize-schema=false`。表中只保存身份摘要、请求摘要、任务 ID 和创建时间，不保存 API Key 或提示词。

提交前先独立提交数据库预留记录，同一租户/执行重试复用任务。若进程在请求完成前崩溃或网络结果不确定，则拒绝自动再提交；须到方舟核对，不能靠重试消除不确定性。记录至少保留到工作流可重试期限结束，删除后同一执行可能重新提交。

取消先查询状态，仅对排队任务发送 DELETE；运行中返回取消不可用，已经结束的任务不删除记录。此版本支持文生视频，不包含图生视频、多模态参考和平台视频模型目录管理。

实现参照[火山官方 SDK 任务接口](https://github.com/volcengine/volcengine-python-sdk/blob/master/volcenginesdkarkruntime/resources/content_generation/tasks.py)和[任务响应类型](https://github.com/volcengine/volcengine-python-sdk/blob/master/volcenginesdkarkruntime/types/content_generation/content_generation_task.py)。测试使用模拟方舟 HTTP 服务与 H2，未使用真实 API Key 发起付费视频生成。

## 验证

```shell
mvn -pl agent-start-plugins/agent-start-plugin-example,agent-start-plugins/agent-start-plugin-seedance -am test
```

原示例 artifactId 保持 `agent-start-plugin-example`；命令行也可用 `-pl :agent-start-plugin-example`，文件路径已迁移到集合目录下。
