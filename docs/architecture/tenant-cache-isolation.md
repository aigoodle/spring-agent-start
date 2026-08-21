# Tenant and runtime-node cache isolation

This inventory is the review baseline for every reusable in-process or distributed cache. A cache
that contains tenant-owned data must include the trusted tenant id. Channel runtime data must also
include the runtime node whenever two sidecars can expose different state.

| Cache/state | Isolation key | Evidence / rule |
|---|---|---|
| Channel catalog | `tenantId + runtimeNodeId` | `ChannelCatalogService.CacheKey`; force refresh and targeted invalidation use the same key |
| Outbound quota | `tenantId + provider + runtimeNodeId + accountId` | `ChannelOutboundRateLimiter.Scope`; a shared Redis implementation must preserve this exact scope |
| Working memory | `tenantId + ownerId + conversationId` | `LayeredMemoryManager.WorkingKey`; prevents employee and conversation crossover |
| Vector store handle | `tenantId + datasetId` | `VectorStoreManager.StoreKey`; tenant-explicit eviction is preferred |
| Model runtime | `tenantId + endpointId + modelType` | `ModelInstanceFactory.CacheKey`; business changes use tenant-scoped eviction and cannot invalidate another tenant's matching endpoint id |
| Credential encryptor | `tenantId` | model and Connector derived-encryptor maps never reuse a tenant-derived key |
| Active Agent observation | `tenantId + runId + resumed` | prevents a repeated/imported run id in another tenant from closing the wrong trace |
| In-memory Agent run/event stream | `tenantId + runId` | `InMemoryAgentRunStore.RunKey`; an imported/custom run id may safely repeat across tenants and legacy tenant-less lookup fails when ambiguous |
| Active Agent execution thread | `tenantId + runId` | cancellation can only interrupt the execution registered for the trusted tenant |

The OpenClaw connector-definition fallback cache and MCP client/tool discovery are deliberately
deployment-scoped, not tenant-owned caches: their configuration is supplied by the host process and
contains no tenant account credentials. A future feature that permits tenants to configure MCP servers
must introduce `tenantId + runtimeNodeId + serverId` managers instead of reusing the deployment-scoped
`McpClientManager`.

Do not use browser `X-Tenant-Id` values to construct these keys. The tenant must come from the host
authentication snapshot or, for inbound channel messages, the persisted account-to-tenant binding.

Required regression tests:

- the same catalog query on different tenants or nodes performs independent discovery;
- the same account id in different tenants/nodes receives an independent rate-limit window;
- the same conversation id for two employees or tenants never shares working memory;
- the same dataset/model/run id in different tenants never shares a handle, event stream, execution thread or active observation;
- targeted invalidation cannot evict or reveal another tenant's value.
