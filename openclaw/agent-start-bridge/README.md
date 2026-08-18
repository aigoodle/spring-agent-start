# Spring Agent Start OpenClaw Bridge

Private OpenClaw plugin that exposes the Gateway's public `tools.catalog` and
`tools.invoke` RPC methods through a small authenticated HTTP contract consumed by
`agent-start-connector-openclaw`.

The bridge is intended for an internal Docker network. Set a strong `serviceToken`,
keep the Gateway port private, and leave remote installation disabled unless package
prefixes are explicitly allowlisted.

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
`allowInstall` defaults to `false`; when enabled, keep `allowedPackagePrefixes`
restricted to trusted publishers. Then start/restart the Gateway and point Java at it:

```yaml
spring-agent:
  connector:
    openclaw:
      enabled: true
      base-url: http://openclaw:18789
      service-token: ${OPENCLAW_SERVICE_TOKEN}
```

The Bridge supports catalog discovery, invocation, configuration, enable/disable,
allowlisted installation, and uninstall. The browser calls the Java management API;
it never receives the Bridge token or connects to OpenClaw directly.
