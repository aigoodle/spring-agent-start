package io.github.aigoodle.web.support;

import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.UserContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/** Safe default: only host-authenticated tenant administrators may bypass the ownership model. */
public final class DefaultChannelAdministrationPolicy implements ChannelAdministrationPolicy {
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "TENANT_ADMIN", "CONNECTOR_ADMIN");

    @Override public void requireAdministrator() {
        CurrentUser user = UserContextHolder.get();
        boolean allowed = user != null && user.getRoles() != null && user.getRoles().stream()
                .map(String::toUpperCase).anyMatch(ADMIN_ROLES::contains);
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "tenant administrator role is required");
    }
}
