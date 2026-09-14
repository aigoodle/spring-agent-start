# Shared workflow resource tenancy

A published GLOBAL application has two tenant identities during execution:

| Operation | Tenant |
| --- | --- |
| Published workflow definition | Application owner, through the restricted shared lookup |
| LLM model selection, credentials, embedding and reranking during retrieval | Application owner |
| Configured knowledge datasets and prompt templates | Application owner |
| Workflow-native Agent model | Application owner |
| Conversation, memory, checkpoints, run records | Authenticated caller |
| HTTP/service API business identity, connector installations/connections/actions, triggers | Authenticated caller |

`WorkflowRunOptions.resourceTenantId` is a trusted Java binding set only after shared
publication access is checked. Ordinary workflows default to their executing tenant.
It is not read from node settings, request `data`, or a client-supplied tenant ID.
The checkpoint graph envelope stores the binding separately from graph inputs and
restores it on resume. Iterations inherit it. Resource calls temporarily scope the
SQL tenant and restore it in `finally`; the SQL tenant guard remains enabled.

Sharing the application authorizes use of resources referenced in its published
definition. It does not grant console CRUD access to the owner's resources or
permission to operate the owner's business connections. There is no unscoped
cross-tenant search and no fallback to another tenant's credentials when resolution
fails. Tenant-specific application overrides continue to take precedence by code.

Extension node authors should use `ExecutionContext.withResourceTenant` and
`resourceTenant()` only for configuration/resource resolution. Keep business
operations, memory and writes to run state under `getTenantId()`. Connector/plugin
actions remain caller-scoped; sharing a workflow does not implicitly share an
external account connection or plugin's business data.
