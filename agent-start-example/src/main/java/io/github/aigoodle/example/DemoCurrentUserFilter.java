package io.github.aigoodle.example;

import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.PrincipalType;
import io.github.aigoodle.common.context.UserContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Establishes a predictable identity for the runnable local demo only.
 *
 * <p>This is deliberately located in {@code agent-start-example}, not in a starter:
 * production hosts must resolve {@link CurrentUser} from their authenticated principal
 * and must never trust browser-supplied identity headers.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "spring-agent.demo-current-user", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DemoCurrentUserFilter extends OncePerRequestFilter {

    private final String defaultUserId;
    private final String defaultUsername;
    private final String defaultTenantId;
    private final Set<String> defaultRoles;

    public DemoCurrentUserFilter(
            @Value("${spring-agent.demo-current-user.user-id:demo-user}") String defaultUserId,
            @Value("${spring-agent.demo-current-user.username:Demo Administrator}") String defaultUsername,
            @Value("${spring-agent.demo-current-user.tenant-id:default}") String defaultTenantId,
            @Value("${spring-agent.demo-current-user.roles:TENANT_ADMIN,CONNECTOR_ADMIN}") String defaultRoles) {
        this.defaultUserId = defaultUserId;
        this.defaultUsername = defaultUsername;
        this.defaultTenantId = defaultTenantId;
        this.defaultRoles = roles(defaultRoles);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CurrentUser user = CurrentUser.builder()
                .userId(headerOrDefault(request, "X-Demo-User-Id", defaultUserId))
                .username(headerOrDefault(request, "X-Demo-Username", defaultUsername))
                .tenantId(headerOrDefault(request, "X-Tenant-Id", defaultTenantId))
                .roles(roles(headerOrDefault(request, "X-Demo-Roles", String.join(",", defaultRoles))))
                .principalType(PrincipalType.USER)
                .build();
        try (UserContextHolder.ContextScope ignored = UserContextHolder.openScope(user)) {
            filterChain.doFilter(request, response);
        }
    }

    private static String headerOrDefault(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Set<String> roles(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(role -> !role.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
