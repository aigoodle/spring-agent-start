package io.github.aigoodle.web.support;

import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultChannelAdministrationPolicyTest {
    private final DefaultChannelAdministrationPolicy policy = new DefaultChannelAdministrationPolicy();

    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test void rejectsAnonymousAndOrdinaryEmployees() {
        assertThatThrownBy(policy::requireAdministrator).isInstanceOf(ResponseStatusException.class);
        UserContextHolder.set(CurrentUser.builder().tenantId("tenant-a").userId("employee-1")
                .roles(Set.of("EMPLOYEE")).build());
        assertThatThrownBy(policy::requireAdministrator).isInstanceOf(ResponseStatusException.class);
    }

    @Test void acceptsHostAuthenticatedTenantAdministrator() {
        UserContextHolder.set(CurrentUser.builder().tenantId("tenant-a").userId("admin-1")
                .roles(Set.of("TENANT_ADMIN")).build());
        assertThatCode(policy::requireAdministrator).doesNotThrowAnyException();
    }
}
