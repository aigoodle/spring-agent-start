package io.github.aigoodle.mcp.auth.spi;

import io.github.aigoodle.common.context.CurrentUser;

/** Converts one already-extracted bearer token into a trusted caller. */
@FunctionalInterface
public interface McpTokenAuthenticator {

    CurrentUser authenticate(String token);
}
