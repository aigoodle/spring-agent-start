# 宿主组织目录 SPI 接入

Agent Start 不保存租户、部门、人员主数据。嵌入项目通过只读 SPI 暴露自己的组织目录，
Agent Start 和宿主业务模块只依赖稳定的目录 DTO，不依赖宿主数据库实体或远程接口协议。

接口和模型位于 `agent-start-common` 的 `io.github.aigoodle.common.directory` 包：

- `TenantDirectoryProvider`：租户单查、批量查询。
- `DepartmentDirectoryProvider`：部门单查、批量查询、直接下级和全部下级。
- `UserDirectoryProvider`：人员单查、批量查询、按部门分页查询和租户内搜索。
- `TenantInfo`、`DepartmentInfo`、`UserInfo`：跨模块使用的只读视图。
- `UserQuery`、`UserPage`：人员分页查询协议，页码从 1 开始，每页最多 200 条。

`DepartmentDirectoryProvider` 继承已有的 `DepartmentHierarchyProvider`。注册前者后，应用按部门授权、
“包含下级部门”等已有功能会直接复用同一个实现，不需要同时注册两个 Bean。

## 宿主实现

下面的 `TenantService`、`DepartmentService` 和 `EmployeeService` 代表宿主已有的业务服务，
方法名称仅作示例。实现必须始终使用传入的 `tenantId` 限定查询范围。

```java
@Configuration
class AgentStartDirectoryConfiguration {

    @Bean
    TenantDirectoryProvider tenantDirectoryProvider(TenantService tenants) {
        return new TenantDirectoryProvider() {
            @Override
            public Optional<TenantInfo> findTenant(String tenantId) {
                return tenants.findById(tenantId).map(HostDirectoryMapper::tenant);
            }

            @Override
            public Map<String, TenantInfo> findTenants(Collection<String> tenantIds) {
                return tenants.findByIds(tenantIds).stream()
                        .map(HostDirectoryMapper::tenant)
                        .collect(Collectors.toUnmodifiableMap(TenantInfo::id, Function.identity()));
            }
        };
    }

    @Bean
    DepartmentDirectoryProvider departmentDirectoryProvider(DepartmentService departments) {
        return new DepartmentDirectoryProvider() {
            @Override
            public Optional<DepartmentInfo> findDepartment(String tenantId, String departmentId) {
                return departments.find(tenantId, departmentId).map(HostDirectoryMapper::department);
            }

            @Override
            public Map<String, DepartmentInfo> findDepartments(
                    String tenantId, Collection<String> departmentIds) {
                return departments.findByIds(tenantId, departmentIds).stream()
                        .map(HostDirectoryMapper::department)
                        .collect(Collectors.toUnmodifiableMap(DepartmentInfo::id, Function.identity()));
            }

            @Override
            public List<DepartmentInfo> findChildren(String tenantId, String parentDepartmentId) {
                return departments.findChildren(tenantId, parentDepartmentId).stream()
                        .map(HostDirectoryMapper::department).toList();
            }

            @Override
            public List<DepartmentInfo> findDescendants(String tenantId, String departmentId) {
                // 返回所有层级下级，不包含 departmentId 自身。
                return departments.findDescendants(tenantId, departmentId).stream()
                        .map(HostDirectoryMapper::department).toList();
            }
        };
    }

    @Bean
    UserDirectoryProvider userDirectoryProvider(EmployeeService employees) {
        return new HostUserDirectoryProvider(employees);
    }
}
```

人员实现示例：

```java
final class HostUserDirectoryProvider implements UserDirectoryProvider {
    private final EmployeeService employees;

    HostUserDirectoryProvider(EmployeeService employees) {
        this.employees = employees;
    }

    @Override
    public Optional<UserInfo> findUser(String tenantId, String userId) {
        return employees.find(tenantId, userId).map(HostDirectoryMapper::user);
    }

    @Override
    public Map<String, UserInfo> findUsers(String tenantId, Collection<String> userIds) {
        // 应使用一次 SQL/远程批量请求，不能循环调用 findUser。
        return employees.findByIds(tenantId, userIds).stream()
                .map(HostDirectoryMapper::user)
                .collect(Collectors.toUnmodifiableMap(UserInfo::id, Function.identity()));
    }

    @Override
    public UserPage findUsersByDepartment(String tenantId, Collection<String> departmentIds,
                                          boolean includeDescendants, UserQuery query) {
        HostPage<Employee> page = employees.findByDepartments(
                tenantId, departmentIds, includeDescendants,
                query.keyword(), query.status(), query.page(), query.size());
        return new UserPage(page.items().stream().map(HostDirectoryMapper::user).toList(),
                page.total(), query.page(), query.size());
    }

    @Override
    public UserPage searchUsers(String tenantId, UserQuery query) {
        HostPage<Employee> page = employees.search(
                tenantId, query.keyword(), query.status(), query.page(), query.size());
        return new UserPage(page.items().stream().map(HostDirectoryMapper::user).toList(),
                page.total(), query.page(), query.size());
    }
}
```

## 业务模块使用

通过构造器注入所需的最小接口，不要直接依赖宿主的用户表 Mapper：

```java
@Service
class ApprovalCandidateService {
    private final UserDirectoryProvider users;

    ApprovalCandidateService(UserDirectoryProvider users) {
        this.users = users;
    }

    UserPage currentDepartmentUsers(String keyword) {
        String tenantId = UserContextHolder.currentTenantId();
        String departmentId = UserContextHolder.currentDepartmentId();
        if (departmentId == null) {
            return UserPage.empty(UserQuery.firstPage(keyword));
        }
        return users.findUsersByDepartment(
                tenantId, Set.of(departmentId), false, UserQuery.firstPage(keyword));
    }

    UserPage currentDepartmentTreeUsers(String keyword) {
        String tenantId = UserContextHolder.currentTenantId();
        String departmentId = UserContextHolder.currentDepartmentId();
        UserQuery query = UserQuery.firstPage(keyword);
        if (departmentId == null) return UserPage.empty(query);
        return users.findUsersByDepartment(tenantId, Set.of(departmentId), true, query);
    }
}
```

按 ID 批量回显人员：

```java
Map<String, UserInfo> creators = users.findUsers(
        UserContextHolder.currentTenantId(), creatorIds);
```

获取部门树：

```java
List<DepartmentInfo> children = departments.findChildren(tenantId, parentId);
List<DepartmentInfo> wholeSubtree = departments.findDescendants(tenantId, departmentId);
Set<String> descendantIds = departments.descendantDepartmentIds(tenantId, departmentId);
```

## 与当前登录人的关系

目录 SPI 负责查询资料，`CurrentUser` 负责表达已经认证的调用者，两者不能互相替代。宿主应在自己的
登录过滤器中从可信会话或 Token 构造 `CurrentUser`，而不是相信浏览器传入的人员或租户 ID。

```java
CurrentUser current = CurrentUser.builder()
        .tenantId(loginUser.tenantId())
        .userId(loginUser.userId())
        .username(loginUser.displayName())
        .departmentId(loginUser.primaryDepartmentId())
        .roleIds(loginUser.roleIds())
        .roles(loginUser.roleCodes())
        .principalType(PrincipalType.USER)
        .build();

departmentDirectoryProvider.populateDepartmentIds(current);

try (UserContextHolder.ContextScope ignored = UserContextHolder.openScope(current)) {
    filterChain.doFilter(request, response);
}
```

`departmentId` 表示人员的当前主部门；`departmentIds` 在现有上下文中表示当前部门及其下级的数据范围，
不是人员实际兼职所属的全部部门。人员实际所属部门集合由 `UserInfo.departmentIds` 表达。

## 实现约束

1. 所有查询都必须在 `tenantId` 内执行；相同人员或部门 ID 在不同租户中不能串数据。
2. 批量方法应使用批量 SQL 或一次远程请求，建议宿主限制一次最多 500～1000 个 ID。
3. 部门人员与搜索接口必须分页；不要通过一个接口加载租户全部人员。
4. 不存在或调用者不可见的数据：单查返回 `Optional.empty()`，批量结果不包含对应 key。
5. `findDescendants` 只返回下级，不包含传入部门自身，并且必须覆盖所有层级。
6. 组织目录故障在权限判断场景必须向上传递，不能用空结果或缓存旧值降级放行。
7. 可对租户、部门和人员展示数据做短时缓存；人员禁用、调岗、部门移动后应主动失效缓存。
8. `email`、`mobile` 是敏感可选字段。宿主可按权限置空，不应为了普通名称回显扩大数据暴露范围。
9. `UserInfo`、`DepartmentInfo`、`TenantInfo` 是跨模块 DTO，不要把宿主 ORM 实体直接返回给调用方。

应用数据权限的配置和判断规则见
[`app-data-permissions.md`](app-data-permissions.md)。可信租户上下文和持久化隔离见
[`tenant-persistence.md`](tenant-persistence.md)。
