package io.github.aigoodle.web.support;

import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultChannelRuntimeAdministrationPolicyTest {
    private final DefaultChannelRuntimeAdministrationPolicy policy =
            new DefaultChannelRuntimeAdministrationPolicy();

    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test void tenantAdministratorCannotAccessSharedRuntimeAccounts() {
        UserContextHolder.set(CurrentUser.builder().tenantId("tenant-a").userId("admin-a")
                .roles(Set.of("TENANT_ADMIN", "CONNECTOR_ADMIN")).build());
        assertThatThrownBy(policy::requireRuntimeAdministrator)
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }

    @Test void explicitDeploymentAdministratorCanAccessRuntimeAccounts() {
        UserContextHolder.set(CurrentUser.builder().tenantId("platform").userId("ops-1")
                .roles(Set.of("DEPLOYMENT_ADMIN")).build());
        assertThatCode(policy::requireRuntimeAdministrator).doesNotThrowAnyException();
    }
}
