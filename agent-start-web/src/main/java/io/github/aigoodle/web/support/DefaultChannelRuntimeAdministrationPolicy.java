package io.github.aigoodle.web.support;

import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.UserContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/** Safe default: tenant administrators cannot enumerate or mutate shared runtime accounts. */
public final class DefaultChannelRuntimeAdministrationPolicy implements ChannelRuntimeAdministrationPolicy {
    private static final Set<String> RUNTIME_ADMIN_ROLES =
            Set.of("PLATFORM_ADMIN", "SYSTEM_ADMIN", "DEPLOYMENT_ADMIN");

    @Override public void requireRuntimeAdministrator() {
        CurrentUser user = UserContextHolder.get();
        boolean allowed = user != null && user.getRoles() != null && user.getRoles().stream()
                .map(String::toUpperCase).anyMatch(RUNTIME_ADMIN_ROLES::contains);
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "deployment administrator role is required for shared channel runtime accounts");
    }
}
