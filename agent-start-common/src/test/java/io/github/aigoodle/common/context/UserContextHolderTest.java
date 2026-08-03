package io.github.aigoodle.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextHolderTest {

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void nestedScopesRestoreThePreviousUser() {
        CurrentUser outerUser = user("outer", "tenant-a");
        CurrentUser innerUser = user("inner", "tenant-b");

        try (UserContextHolder.ContextScope ignored = UserContextHolder.openScope(outerUser)) {
            assertThat(UserContextHolder.currentUserId()).isEqualTo("outer");
            try (UserContextHolder.ContextScope nested = UserContextHolder.openScope(innerUser)) {
                assertThat(UserContextHolder.currentUserId()).isEqualTo("inner");
            }
            assertThat(UserContextHolder.currentUserId()).isEqualTo("outer");
        }

        assertThat(UserContextHolder.get()).isNull();
    }

    @Test
    void callAsRestoresContextWhenOperationFails() {
        CurrentUser originalUser = user("original", "tenant-a");
        UserContextHolder.set(originalUser);

        assertThatThrownBy(() -> UserContextHolder.callAs(user("temporary", "tenant-b"), () -> {
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(UserContextHolder.get()).isSameAs(originalUser);
    }

    @Test
    void blankTenantUsesProjectDefault() {
        String tenantId = UserContextHolder.callAs(user("user-1", "  "),
                UserContextHolder::currentTenantId);

        assertThat(tenantId).isEqualTo(UserContextHolder.DEFAULT_TENANT);
    }

    private static CurrentUser user(String userId, String tenantId) {
        return CurrentUser.builder()
                .userId(userId)
                .tenantId(tenantId)
                .build();
    }
}
