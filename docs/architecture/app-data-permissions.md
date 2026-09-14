# 应用数据权限接入

应用目录 `goodle_apps.data_access_mode` 支持 `ALL`（兼容原租户可见范围）和
`RESTRICTED`（必须命中授权）。新表 `goodle_app_permissions` 按
`tenant_id + app_id + subject_type + subject_id` 唯一关联宿主业务 ID。
`subject_type` 为 `USER`、`ROLE`、`DEPARTMENT`；`include_descendants` 仅对部门有效。
不复制宿主组织架构，也不接受请求体指定当前用户或租户。

已有数据库执行 `pgsql/migrations/20260912_app_data_permissions.sql`。
模块 schema 与 PostgreSQL 初始化脚本也包含相同升级。旧应用默认 ALL；
RESTRICTED 且空授权列表表示普通用户全部不可见，不会退回公开访问。

## 业务方提供用户上下文和部门 SPI

```java
@Bean
DepartmentHierarchyProvider departmentHierarchyProvider(OrganizationService organization) {
    // 返回所有层级下级部门；必须按 tenantId 查询，使用业务系统自己的组织表。
    return (tenantId, departmentId) ->
            organization.findAllDescendantIds(tenantId, departmentId);
}

// 放在宿主现有登录鉴权拦截器中；不要从未校验的请求头直接读取这些字段。
CurrentUser current = CurrentUser.builder()
        .userId(loginUser.getId())
        .tenantId(loginUser.getTenantId())
        .roles(loginUser.getRoleCodes())
        .roleIds(loginUser.getRoleIds())
        .departmentId(loginUser.getDepartmentId())
        .build();
departmentHierarchyProvider.populateDepartmentIds(current);
UserContextHolder.set(current);
// 请求结束 finally 中 UserContextHolder.clear()。
```

以上 `OrganizationService`、`loginUser` 方法是宿主接入示意。SPI 注册为 Spring Bean
即可替换默认实现；没有提供 SPI 时仅匹配部门自身。业务方可在 SPI 中缓存组织子树，
组织变更后应失效缓存。SPI 异常向上传递，不会放行；空结果表示无下级。
授权只存原始部门 ID，读取时解析子树，因此新增或迁移部门无需重写应用授权记录。
列表请求会批量读取授权，同一被授权部门在单次列表中只解析一次。

两个部门字段有不同含义：

- `departmentId`：当前用户所属部门，用于判断应用授权。
- `departmentIds`：当前部门及所有下级的数据范围，供宿主其他数据过滤使用。
  通过 `populateDepartmentIds` 或宿主登录逻辑填充；不会自动扩大应用访问权限。

例如组织结构 `公司 → 项目部 → 研发部 → 一组`：授权项目部并勾选包含下级，
项目部、研发部、一组的用户可访问，公司和其他兄弟部门的用户不因此获得权限。
判断方式是“当前 departmentId 是否在被授权部门及其子树中”，
不能判断“被授权部门是否在当前用户下级列表中”，否则继承方向相反。
`roleIds` 存业务角色主键；`roles` 保留原角色标记语义，不混用 ID 与名称。

## 接口与管理权限

`GET /agent-start/apps/{appId}/permissions` 读取配置，
`PUT /agent-start/apps/{appId}/permissions` 事务内全量替换（也支持 `/agents` 别名）：

```json
{
  "mode": "RESTRICTED",
  "grants": [
    { "type": "DEPARTMENT", "subjectId": "project-100", "includeDescendants": true },
    { "type": "ROLE", "subjectId": "role-200", "includeDescendants": false },
    { "type": "USER", "subjectId": "user-300", "includeDescendants": false }
  ]
}
```

规则为 OR。权限配置默认要求同租户 `ADMIN` / `TENANT_ADMIN` 角色标记，
或可信上下文 scope `apps:permissions:write`；这些管理员也可查看受限应用。
业务方可以替换 `AppPermissionService` Bean 定制管理员判断与授权策略。
读取和替换配置均校验管理权限；普通查看授权不允许修改、删除受限应用、
修改其工作流或管理 API 密钥。ALL 模式保留旧有宿主管理策略。
重复主体、空 ID、未知类型、非部门的下级标记均拒绝，替换同一应用时加行锁。

权限覆盖应用列表/详情/模型配置/选择器、按编码解析、已发布 Agent 执行，
以及关联应用的工作流定义读取、列表和保存发布。独立且没有应用目录关联的工作流
保持原有租户策略。底层 Mapper、直接执行自建 AgentDefinition/临时图是可信嵌入式 API，
宿主不应将它们作为绕过服务层鉴权的公网接口；其他模块的数据、会话归属和运行任务管理
仍须遵循各自的宿主鉴权策略，本功能不是全系统 RBAC。

数据授权不扩大租户范围。GLOBAL 的跨租户共享只在 ALL 模式保留，受限应用不会因为
不同租户出现同名部门/角色 ID 而开放。可信 `APP_API_KEY` 身份可访问它绑定的同租户应用，
这是独立的 API 能力；普通 `appId` 参数不构成该身份，宿主需先验证密钥。
异步、定时和渠道调用受限应用也必须传递可信上下文，缺少上下文不放行。
MVC 使用 `UserContextHolder`；WebFlux 沿用 Reactor Context 桥接，SSE 虚拟线程已传递上下文。

## Vue 组件

`AgentAppsPage` 卡片菜单新增“数据权限”，可填写业务用户/角色/部门 ID，
并选择部门包含下级。保存独立调用授权接口，不与应用基本信息保存混合。
加载失败时禁用保存，避免空配置覆盖原授权。
SDK 提供 `client.agents.getPermissions(id)`、`updatePermissions(id, settings)`，
类型 `AppPermissionSettings` 可从 `vue-agent-start/client` 导入。
