FROM node:22-bookworm-slim AS bridge-build
WORKDIR /src/bridge
COPY openclaw/agent-start-bridge/package.json openclaw/agent-start-bridge/package-lock.json ./
RUN npm ci
COPY openclaw/agent-start-bridge/tsconfig.json openclaw/agent-start-bridge/openclaw.plugin.json ./
COPY openclaw/agent-start-bridge/src ./src
RUN npm run build

FROM node:22-bookworm-slim
ARG OPENCLAW_VERSION=2026.7.1-2
RUN npm install --global "openclaw@${OPENCLAW_VERSION}"
WORKDIR /opt/agent-start-bridge
COPY openclaw/agent-start-bridge/package.json openclaw/agent-start-bridge/openclaw.plugin.json ./
COPY --from=bridge-build /src/bridge/dist ./dist
COPY openclaw/docker-entrypoint.sh /usr/local/bin/agent-start-openclaw
RUN chmod +x /usr/local/bin/agent-start-openclaw
ENV OPENCLAW_STATE_DIR=/var/lib/openclaw \
    OPENCLAW_CONFIG_PATH=/var/lib/openclaw/openclaw.json
EXPOSE 18789
ENTRYPOINT ["agent-start-openclaw"]
