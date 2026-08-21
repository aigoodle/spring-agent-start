package io.github.aigoodle.server.config;

import io.github.aigoodle.common.context.UserContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DemoCurrentUserWebFilterTest {
    @Test
    void callerCannotForgeTenantUserOrAdministrativeRolesThroughDemoHeaders() {
        DemoCurrentUserWebFilter filter = new DemoCurrentUserWebFilter(
                "configured-user", "Configured User", "configured-tenant", "TENANT_ADMIN");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/agent-start/apps")
                .header("X-Tenant-Id", "forged-tenant")
                .header("X-Demo-User-Id", "forged-user")
                .header("X-Demo-Username", "Forged User")
                .header("X-Demo-Roles", "SYSTEM_ADMIN,PLATFORM_ADMIN"));

        filter.filter(exchange, ignored -> Mono.fromRunnable(() -> {
            assertThat(UserContextHolder.currentTenantId()).isEqualTo("configured-tenant");
            assertThat(UserContextHolder.currentUserId()).isEqualTo("configured-user");
            assertThat(UserContextHolder.get().getUsername()).isEqualTo("Configured User");
            assertThat(UserContextHolder.get().getRoles()).isEqualTo(Set.of("TENANT_ADMIN"));
        })).block();

        assertThat(UserContextHolder.get()).isNull();
    }
}
