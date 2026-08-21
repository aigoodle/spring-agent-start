#!/bin/sh
set -eu

mkdir -p "$OPENCLAW_STATE_DIR"
mkdir -p "$OPENCLAW_STATE_DIR/workspace"
openclaw config set gateway.mode local
openclaw config set gateway.port 18789 --strict-json
openclaw config set gateway.auth.mode token
openclaw config set agents.defaults.workspace "$OPENCLAW_STATE_DIR/workspace"

if ! openclaw config get plugins.load.paths 2>/dev/null | grep -q '/opt/agent-start-bridge'; then
  openclaw plugins install /opt/agent-start-bridge --link
fi

openclaw config set plugins.entries.agent-start-bridge.enabled true --strict-json
openclaw config set plugins.allow '["agent-start-bridge","qqbot"]' --strict-json
openclaw config set plugins.entries.agent-start-bridge.hooks.allowConversationAccess true --strict-json
openclaw config set plugins.entries.agent-start-bridge.config.allowInstall "${OPENCLAW_ALLOW_INSTALL:-false}" --strict-json
if [ -n "${AGENT_START_EVENT_SINK_URL:-}" ]; then
  openclaw config set plugins.entries.agent-start-bridge.config.eventSinkUrl "$AGENT_START_EVENT_SINK_URL"
fi

exec openclaw gateway run --bind lan --port 18789 --token "$OPENCLAW_GATEWAY_TOKEN" --allow-unconfigured
