package io.github.aigoodle.tool.execution;

import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.PrincipalType;
import io.github.aigoodle.common.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserToolExecutionContextProviderTest {

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void snapshotsTrustedTenantActorAndAuthorisationMetadata() {
        CurrentUser user = CurrentUser.builder()
                .tenantId("tenant-a")
                .userId("employee-7")
                .appId("host-app")
                .principalType(PrincipalType.USER)
                .roles(Set.of("support"))
                .scopes(Set.of("tool:call"))
                .build();
        user.put("authorization", "Bearer user-token");
        UserContextHolder.set(user);

        ToolExecutionContext context = new CurrentUserToolExecutionContextProvider().currentContext();

        assertThat(context.tenantId()).isEqualTo("tenant-a");
        assertThat(context.ownerId()).isEqualTo("employee-7");
        assertThat(context.metadata())
                .containsEntry("principalType", "USER")
                .containsEntry("appId", "host-app")
                .containsEntry("roles", Set.of("support"))
                .containsEntry("scopes", Set.of("tool:call"))
                .containsEntry("authorization", "Bearer user-token");
    }

    @Test
    void remainsAnonymousOutsideHostAuthenticationScope() {
        assertThat(new CurrentUserToolExecutionContextProvider().currentContext())
                .isEqualTo(ToolExecutionContext.anonymous());
    }
}
