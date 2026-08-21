# Spring Agent Start OpenClaw Bridge

Private OpenClaw plugin that exposes the Gateway's public `tools.catalog` and
`tools.invoke` RPC methods through a small authenticated HTTP contract consumed by
`agent-start-connector-openclaw`.

The bridge is intended for an internal Docker network. Set a strong `serviceToken`,
keep the Gateway port private, and leave remote installation disabled unless the
deployment administrator needs to manage plugins through the Java facade.

The initial compatibility target is OpenClaw `2026.7.1-2`. Tool catalog RPC currently
does not expose executable JSON schemas, so catalog tools use an open-object schema;
runtime argument validation remains owned by OpenClaw's `tools.invoke` implementation.

## Install into OpenClaw

```bash
npm ci
npm run build
openclaw plugins install .
openclaw plugins enable agent-start-bridge
```

Configure the plugin entry in the OpenClaw config with a strong `serviceToken`.
Also supply a Gateway operator token through `OPENCLAW_GATEWAY_TOKEN` (recommended)
or the sensitive `gatewayToken` plugin setting. The Bridge uses OpenClaw's public
CLI Gateway client because direct in-process Gateway RPC is restricted to trusted
official plugins in the current compatibility release.
`allowInstall` defaults to `false`. Enabling it allows any valid npm package name or
ClawHub identifier because private deployments commonly install their own plugins.
Treat the Java lifecycle endpoint as a deployment-administrator capability; the
Bridge token and Gateway must remain private. Then start/restart the Gateway and
point Java at it:

```yaml
spring-agent:
  connector:
    openclaw:
      enabled: true
      base-url: http://openclaw:18789
      service-token: ${OPENCLAW_SERVICE_TOKEN}
```

The Bridge supports catalog discovery, invocation, configuration, enable/disable,
administrator-controlled installation, and uninstall. The browser calls the Java management API;
it never receives the Bridge token or connects to OpenClaw directly.

Channel sends accept an Outbox `idempotencyKey`. Successful acknowledgements are stored atomically under
`OPENCLAW_STATE_DIR` (the Compose volume maps this directory), capped to the newest 10,000 entries. A retry
after a Java worker or OpenClaw process restart returns the stored result with `idempotencyReplay=true`
instead of sending the QQ message again.

This provides durable retry deduplication after an acknowledgement has been stored. Like every adapter
whose upstream send API has no native idempotency token, the tiny crash window between upstream acceptance
and storing that acknowledgement remains at-least-once rather than mathematically exactly-once.
