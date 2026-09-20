# Embedded tenant persistence boundary

Agent Start does not own tenant, employee or login tables. The embedding application authenticates
the caller and injects a trusted `CurrentUser` into `UserContextHolder`. API request DTOs are not a
trusted source of `tenantId`.

The standalone Server demo follows the same rule: its user, tenant and roles come only from
`spring-agent.demo.*` deployment properties. Request headers such as `X-Tenant-Id`,
`X-Demo-User-Id` and `X-Demo-Roles` are ignored and cannot switch tenant or elevate privileges.
Production hosts must set `spring-agent.demo.enabled=false` and install their own authenticated
principal bridge. Workflow, prompt-template and provider-credential HTTP DTOs deliberately expose no
`tenantId` property; the embeddable Java service APIs retain explicit tenant arguments for trusted
background jobs and host integrations.

The demo exposes `/auth/login`, `/auth/logout`, `/auth/codes` and `/user/info` only to satisfy the
sample Vben application's login workflow. Its `demo-session` access token is a UI state marker, not
a credential: the backend neither parses nor trusts it. `DemoCurrentUserWebFilter` reconstructs the
configured identity for every request. A production host should disable the complete demo facade:

```yaml
spring-agent:
  demo:
    enabled: false
```

It then authenticates with its existing JWT/session/SSO infrastructure and maps the verified
principal to `CurrentUser`. Agent Start intentionally owns no user, tenant or password tables.

`agent-start-persistence` adds a MyBatis interceptor with two checks for protected tables:

1. reads, updates and deletes must contain a `tenant_id` predicate; inserts must contain the column;
2. for authenticated calls, the actual bound tenant parameter must equal the host-injected tenant.

The guard validates SQL rather than silently rewriting it. This makes missing tenant conditions fail
in development and production instead of concealing unsafe repository code.

```yaml
spring-agent:
  persistence:
    tenant-guard:
      enabled: true
      additional-tables:
        - host_business_record
```

The built-in table set currently contains the audited Connector/channel domain, the complete Agent
persistence domain (`goodle_apps`, `goodle_agent_versions`, `goodle_app_model_configs`,
`goodle_app_annotations`, `goodle_app_annotation_settings`, `goodle_conversations`, `goodle_runs`,
`goodle_run_events`, `goodle_api_tokens`, `goodle_app_sites`, `goodle_tags`, `goodle_tag_bindings`),
workflow definitions and runs
(`goodle_workflows`, `goodle_workflow_runs`), and the complete knowledge persistence domain
(`goodle_dataset`, `goodle_documents`, `goodle_document_segments`,
`goodle_document_ingest_queue`, `goodle_dataset_query`), memory (`goodle_memories`), trigger definitions
and invocation history (`goodle_app_triggers`, `goodle_trigger_invocations`), tenant
model instances, provider credentials, model enablement and tenant defaults (`goodle_model`,
`goodle_provider_credential`, `goodle_provider_model_setting`, `goodle_tenant_default_model`) and cannot be
removed through configuration. Knowledge ingestion messages carry the tenant captured by the trusted
request context, so background workers can query and mutate queue, document and segment rows without
using a tenant-guard bypass. Applications may add their own tables with the property above.

Cross-tenant schedulers and authentication-bootstrap credential lookups must enter
`TenantSqlScope.bypass(...)` explicitly. The bypass is intended only for reviewed workers that discover
tenant-owned rows and then re-enter tenant-scoped services, or for resolving an opaque credential whose
tenant is not known before authentication. Never use it to bypass an already-authenticated caller's
tenant. The older `ConnectorTenantScope` remains as a source-compatible facade and delegates to the
same scope.

Current reviewed discovery/maintenance uses are limited to due-trigger scanning, opaque webhook-path
lookup, API bearer-token resolution and expired-memory cleanup. Trigger claiming, invocation persistence, dispatch, memory recall and
access reinforcement all return to explicit tenant predicates after discovery.

An embedded host can replace the `TenantSqlGuardInterceptor` bean if it already enforces tenancy using
database row-level security. Disabling the guard without an equivalent database policy is unsafe.
