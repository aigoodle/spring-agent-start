package io.github.aigoodle.web.support;

import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultChannelOwnershipPolicyTest {
    private final DefaultChannelOwnershipPolicy policy = new DefaultChannelOwnershipPolicy();

    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test void employeeOnlySeesAndManagesOwnAccount() {
        UserContextHolder.set(CurrentUser.builder().userId("login-1").tenantId("tenant-a")
                .extra(new java.util.HashMap<>(java.util.Map.of("employeeId", "employee-1"))).build());

        assertThat(policy.visibleOwnerId(null)).isEqualTo("employee-1");
        policy.requireAccess("EMPLOYEE", "employee-1");
        assertThatThrownBy(() -> policy.visibleOwnerId("employee-2")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> policy.requireAccess("EMPLOYEE", "employee-2")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void administratorMayUseTenantWideView() {
        UserContextHolder.set(CurrentUser.builder().userId("admin-1").tenantId("tenant-a")
                .roles(Set.of("TENANT_ADMIN")).build());

        assertThat(policy.visibleOwnerId(null)).isNull();
        policy.requireAccess("EMPLOYEE", "employee-2");
    }

    @Test void anonymousContextIsRejected() {
        assertThatThrownBy(() -> policy.visibleOwnerId(null)).isInstanceOf(ResponseStatusException.class);
    }
}
