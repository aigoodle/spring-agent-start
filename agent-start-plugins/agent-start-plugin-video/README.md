# 通用视频生成插件

业务插件 ID：`media.video`。原生工作流节点：`VIDEO_GENERATION`。

调用关系：工作流 → 插件/Connector 网关 → VideoTaskService → VideoModelService → 已注册 ModelProvider → 厂商异步 API。

`agent-start-model` 定义 VideoModel SPI、VIDEO 类型和租户模型解析；`agent-start-provider` 子模块实现厂商协议；本模块负责持久化与提交去重，不保存第二套提供商配置。

## 任务可靠性

- 网络提交前用独立事务保存 SUBMITTING 预留记录。幂等键包含租户和稳定的 executionId；相同 executionId 携带不同输入会被拒绝。
- 成功取得厂商 ID 后保存 QUEUED，任务查询使用加密的原模型端点快照。修改模型或密钥不会悄悄把进行中的任务转移到另一个提供商。
- 工作流保存任务引用并持久化等待，服务重启后继续查询。后台恢复器最多并发轮询 4 个任务，数据库租约协调多个实例，失败使用退避。
- 提交后网络断开、响应缺少任务 ID、提交时进程退出等不确定结果进入 UNKNOWN，不自动发起另一次付费生成。
- 工作流取消时持久化取消请求。厂商任务真正结束前不宣称取消成功；火山运行中任务、万相活动任务不能保证取消，仍可能收费。
- 本地任务期限默认一小时。期限结束后未终结的远程任务标记 EXPIRED，本地停止追踪，不代表厂商停止执行。

## 数据库与部署

需要宿主 DataSource、MyBatis-Plus、PlatformTransactionManager、CredentialCodec、VideoModelService。自动配置扫描 `io.github.aigoodle.plugin.video.mapper`。

持久化沿用项目的 Entity + BaseMapper + Store 分层：`VideoTaskEntity` 映射已有表，普通读写使用 BaseMapper，提交状态转换、租约抢占和完成更新通过 `VideoTaskMapper` 的原子 SQL 实现。Store 注入宿主事务管理器，以 REQUIRES_NEW 保证提交预留先于厂商 HTTP 请求落库，并隔离重复键异常。表结构和已有任务数据无需迁移。

默认自动初始化 `db/plugin-video-schema.sql` 并按数据库元数据创建轮询索引。
生产使用迁移工具时分别执行表 SQL、`db/plugin-video-index.sql`，然后设置：

```yaml
spring-agent:
  plugins:
    video:
      initialize-schema: false
      poll-delay-ms: 5000
```

沿用平台模型凭证加密配置；生产必须使用持久化的独立加密密钥。进行中的视频保存了加密端点快照，轮换/撤销厂商密钥时需考虑这些任务。数据库、加密密钥需要一起备份。任务记录在允许工作流重试期间不可删除，否则会丢失防重依据。

## 当前发布边界

当前支持文生视频、顶层串行/并行工作流；嵌套 ITERATION 的视频节点在图校验阶段拒绝，因为现有迭代引擎没有持久化异步子图等待。

当前输出厂商视频 URL，尚未接入平台对象存储归档；URL 的有效期由厂商决定。需要长期保留的视频不能仅保存这个地址。

UNKNOWN 目前需要运维在厂商核对，不支持在页面自动对账或重绑任务；不能用反复重跑绕过这个状态。本地 EXPIRED 与无法取消的厂商任务也需在厂商核对。

连接测试对 VIDEO 仅执行本地配置检查，返回 `verifiedRemote=false`，不会创建付费任务。

自动化测试覆盖模拟 HTTP 协议、H2 任务持久化、重启、防重、参数与租户隔离。它们不证明真实账号具备视频权限、额度充足，也不证明生产数据库迁移和视频成品可播放。

在宣称整体产品可上线前仍需完成：UNKNOWN 的受控对账入口及审计、视频资产归档与过期策略、生产数据库/鉴权环境验收、真实火山与阿里生成验收和并发负载验证。此清单是发布限制，不应把当前测试通过解释为已完成生产验收。

## 厂商协议依据

- [火山官方任务 SDK](https://github.com/volcengine/volcengine-python-sdk/blob/master/volcenginesdkarkruntime/resources/content_generation/tasks.py)
- [阿里 Wan 文生视频异步 API](https://www.alibabacloud.com/help/en/model-studio/legacy-wan-text-to-video-api-reference)
