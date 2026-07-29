package io.github.aigoodle.common.context;

import java.util.Optional;

/**
 * 当前登录用户的线程级持有器 —— spring-agent-start 各模块获取"当前是谁在调用"的
 * <b>唯一入口</b>。本模块本身不提供登录 / 鉴权能力：由宿主项目在自己的拦截器里
 * {@link #set(CurrentUser)}，请求结束务必 {@link #clear()}，中间任何位置（Service /
 * Mapper / 内部工具）都可通过 {@link #get()} / {@link #currentTenantId()} 取用。
 *
 * <h3>宿主项目接入 —— Spring MVC 场景（Servlet）</h3>
 * <pre>{@code
 * // 1. 在你自己的登录拦截器里
 * public class LoginInterceptor implements HandlerInterceptor {
 *     @Override
 *     public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
 *         MyLoginUser me = SecurityUtils.currentLoginUser();   // 宿主自有登录体系
 *         if (me != null) {
 *             UserContextHolder.set(CurrentUser.builder()
 *                     .userId(me.getId())
 *                     .username(me.getName())
 *                     .tenantId(me.getTenantCode())
 *                     .roles(me.getRoles())
 *                     .build());
 *         }
 *         return true;
 *     }
 *     @Override
 *     public void afterCompletion(HttpServletRequest req, HttpServletResponse resp,
 *                                 Object handler, Exception ex) {
 *         UserContextHolder.clear();   // ★ 必做,防止线程复用串号
 *     }
 * }
 * }</pre>
 *
 * <h3>宿主项目接入 —— Spring WebFlux 场景</h3>
 * <p>WebFlux 每一步调度都可能换线程，ThreadLocal 出了 WebFilter 就不保证还在。
 * 简单可靠的做法：既写 ThreadLocal（同步 API 兼容），又用
 * {@link reactor.core.publisher.Mono#contextWrite(java.util.function.Function)}
 * 把用户挂到 Reactor Context 上，下游通过 {@link #getReactive(reactor.util.context.ContextView)}
 * 或 {@code Mono.deferContextual} 显式拉取：
 * <pre>{@code
 * @Component
 * @Order(-100)
 * public class UserContextWebFilter implements WebFilter {
 *     public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
 *         CurrentUser me = resolveFromHeaders(exchange);       // 宿主自己实现
 *         if (me == null) return chain.filter(exchange);
 *         return chain.filter(exchange)
 *                 .contextWrite(ctx -> ctx.put(UserContextHolder.CONTEXT_KEY, me))
 *                 .doOnSubscribe(s -> UserContextHolder.set(me))
 *                 .doFinally(sig -> UserContextHolder.clear());
 *     }
 * }
 * }</pre>
 *
 * <h3>spring-agent-start 内部用法</h3>
 * <pre>{@code
 * public List<DatasetEntity> list() {
 *     String tenant = UserContextHolder.currentTenantId();
 *     return datasetMapper.selectList(new LambdaQueryWrapper<DatasetEntity>()
 *             .eq(DatasetEntity::getTenantId, tenant));
 * }
 * }</pre>
 *
 * <p>本类是 {@code final} + 私有构造，避免被继承或实例化 —— 全部 API 通过静态方法暴露。
 */
public final class UserContextHolder {

    /**
     * Reactor Context 里存放 {@link CurrentUser} 用的 key。WebFlux 场景下宿主项目
     * 用 {@code ctx.put(UserContextHolder.CONTEXT_KEY, user)} 挂入，下游通过
     * {@link #getReactive(reactor.util.context.ContextView)} 取回。
     */
    public static final String CONTEXT_KEY = "spring-agent.current-user";

    /** {@code tenantId} 为空 / 未登录时的租户兜底值，全项目约定。 */
    public static final String DEFAULT_TENANT = "default";

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
        // no instances
    }

    // ============================================================ 写入 / 清理

    /**
     * 塞入当前线程的用户上下文。宿主项目的拦截器在请求进入时调用。
     * {@code null} 值等价于 {@link #clear()}，方便"未登录 / 匿名"分支复用同一入口。
     */
    public static void set(CurrentUser user) {
        if (user == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(user);
        }
    }

    /**
     * 清空当前线程的用户上下文 —— <b>请求结束务必调用</b>，否则线程池复用时会串号。
     * Spring MVC 建议放在 {@code HandlerInterceptor#afterCompletion}；WebFlux 建议
     * 放在 {@code Mono#doFinally}。
     */
    public static void clear() {
        HOLDER.remove();
    }

    // ============================================================ 同步读取

    /**
     * 取当前线程的用户；未登录 / 未 set 时返回 {@code null}。
     * 需要"必须登录"语义的调用点请用 {@link #require()}。
     */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** 与 {@link #get()} 等价，但用 {@link Optional} 包装。 */
    public static Optional<CurrentUser> tryGet() {
        return Optional.ofNullable(HOLDER.get());
    }

    /**
     * 取当前用户；未登录时抛 {@link IllegalStateException} —— 用于确定"没有匿名分支"
     * 的调用点，避免调用方漏判 null。
     */
    public static CurrentUser require() {
        CurrentUser u = HOLDER.get();
        if (u == null) {
            throw new IllegalStateException(
                    "No CurrentUser in context — host application must set it in its login filter before invoking spring-agent-start APIs");
        }
        return u;
    }

    // ============================================================ 便捷取值

    /**
     * 取当前用户的 tenantId；未登录或 tenantId 为空时返回 {@link #DEFAULT_TENANT}。
     * spring-agent-start 内部所有"按租户过滤"的查询都应通过此方法拿租户键。
     */
    public static String currentTenantId() {
        CurrentUser u = HOLDER.get();
        if (u == null) return DEFAULT_TENANT;
        String t = u.getTenantId();
        return (t == null || t.isBlank()) ? DEFAULT_TENANT : t;
    }

    /** 取当前用户 id；未登录返回 {@code null}。 */
    public static String currentUserId() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.getUserId();
    }

    /** 取当前用户名；未登录返回 {@code null}。 */
    public static String currentUsername() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.getUsername();
    }

    /** 是否已登录（== ThreadLocal 里有值）。 */
    public static boolean isAuthenticated() {
        return HOLDER.get() != null;
    }

    // ============================================================ 反应式桥接

    /**
     * WebFlux 场景下从 Reactor {@link reactor.util.context.ContextView} 里取当前用户。
     * <pre>{@code
     * Mono.deferContextual(ctx -> {
     *     CurrentUser me = UserContextHolder.getReactive(ctx).orElse(null);
     *     ...
     * });
     * }</pre>
     * 依赖 reactor-core：仅在调用方类路径已有 reactor 时才会触发 —— common 模块没有硬依赖。
     */
    public static Optional<CurrentUser> getReactive(reactor.util.context.ContextView ctx) {
        if (ctx == null || !ctx.hasKey(CONTEXT_KEY)) return Optional.empty();
        Object val = ctx.get(CONTEXT_KEY);
        return val instanceof CurrentUser cu ? Optional.of(cu) : Optional.empty();
    }

    // ============================================================ 测试 / 临时切换

    /**
     * 在给定用户上下文中运行一段代码，无论成功失败都恢复到原上下文。
     * 主要给单元测试和内部"切租户跑一次"的场景使用。
     * <pre>{@code
     * UserContextHolder.runAs(mockAdmin, () -> service.deleteById(id));
     * }</pre>
     */
    public static void runAs(CurrentUser user, Runnable action) {
        CurrentUser previous = HOLDER.get();
        try {
            set(user);
            action.run();
        } finally {
            set(previous);
        }
    }
}
