#!/bin/sh
set -eu

mkdir -p "$OPENCLAW_STATE_DIR"
openclaw config set gateway.mode local
openclaw config set gateway.port 18789 --strict-json
openclaw config set gateway.auth.mode token

if ! openclaw config get plugins.load.paths 2>/dev/null | grep -q '/opt/agent-start-bridge'; then
  openclaw plugins install /opt/agent-start-bridge --link
fi

openclaw config set plugins.entries.agent-start-bridge.enabled true --strict-json
openclaw config set plugins.entries.agent-start-bridge.config.allowInstall "${OPENCLAW_ALLOW_INSTALL:-false}" --strict-json

exec openclaw gateway run --bind lan --port 18789 --token "$OPENCLAW_GATEWAY_TOKEN" --allow-unconfigured
