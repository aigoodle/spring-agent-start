# MCP Auth Spring Starter

This starter turns the credential stored in a Spring AI MCP request's transport context into the
shared `CurrentUser`, enforces method/class authorization with AOP, and exposes the original
authorization value while the tool forwards a request to its business API. It includes the Spring
AI streamable WebFlux MCP server and installs the authorization-aware transport provider
automatically.

## Request flow

1. The starter's MCP HTTP transport copies `Authorization` into its transport context under
   `authorization`.
2. `@McpAuthorize` intercepts the tool call and extracts the credential.
3. Either the configured HMAC JWT validator or the application's `McpTokenAuthenticator` resolves a
   trusted `CurrentUser`.
4. Roles/scopes are checked, then `UserContextHolder` is populated only for the duration of the call.
5. Business code uses the existing `UserContextHolder` APIs. A downstream client can read
   `McpCredentialContext.currentAuthorization()` to forward the original credential.
6. Both user and credential contexts are restored in `finally`, including error paths and nested calls.

## Add the dependency

```xml
<dependency>
  <groupId>io.github.aigoodle</groupId>
  <artifactId>agent-start-mcp-auth-spring-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

No separate `spring-ai-starter-mcp-server-webflux`, AOP, validation dependency, or transport
configuration is required in the application. The starter provides those pieces and preserves the
standard `spring.ai.mcp.server.*` configuration surface.

## Option A: shared-secret JWT

```yaml
spring-agent:
  mcp:
    auth:
      jwt:
        enabled: true
        secret: ${MCP_JWT_SECRET} # at least 32 UTF-8 bytes; HS256/384/512
        issuer: https://identity.example.com
        audience: mes-mcp
        tenant-id-claim: tenant_id
        roles-claim: roles
        scopes-claim: scope
```

Validated claims include signature, `exp`, `nbf`, and configured `iss`/`aud`. `sub` is required and
becomes `CurrentUser.userId`. Never put the secret or an inbound token in source control or logs.

## Option B: application-owned token parsing

Publishing this bean replaces the default (fail-closed) authenticator:

```java
@Bean
McpTokenAuthenticator mcpTokenAuthenticator(MyIdentityService identities) {
    return token -> identities.verify(token)  // must reject invalid/expired tokens
        .map(me -> CurrentUser.builder()
            .userId(me.id()).username(me.name()).tenantId(me.tenantId())
            .roles(me.roles()).scopes(me.scopes()).build())
        .orElseThrow(() -> new McpAuthenticationException("Invalid token"));
}
```

Protect a complete tool class or individual methods:

```java
@McpAuthorize(scopes = "mes:read")
public class ProductionTools {
    @McpTool(name = "mes_list_workorders", description = "...")
    public String list(McpSyncRequestContext context) {
        String tenantId = UserContextHolder.currentTenantId();
        String authorization = McpCredentialContext.currentAuthorization();
        return mesClient.list(tenantId, authorization);
    }

    @McpAuthorize(roles = {"ADMIN", "SUPERVISOR"}, roleMatch = McpAuthorize.Match.ANY)
    @McpTool(name = "mes_close_workorder", description = "...")
    public String close(McpSyncRequestContext context, long id) { /* ... */ }
}
```

Only annotated classes/methods are intercepted. This makes anonymous tools explicit, but teams that
want every tool protected should place `@McpAuthorize` on each tool class and enforce that convention
with an architecture test. For reactive work performed after the annotated synchronous method
returns, capture the trusted caller/credential explicitly rather than relying on thread locals.
