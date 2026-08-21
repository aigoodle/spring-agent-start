import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { dirname, join } from "node:path";
import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";

const ROOT = "/agent-start-bridge/v1";
const OPEN_OBJECT_SCHEMA = JSON.stringify({
  type: "object",
  properties: {},
  additionalProperties: true,
});

type BridgeConfig = {
  serviceToken: string;
  runtimeNodeId?: string;
  gatewayUrl?: string;
  gatewayToken?: string;
  eventSinkUrl?: string;
  eventSinkTimeoutMs?: number;
  allowInstall?: boolean;
};

type CatalogTool = {
  id: string;
  label?: string;
  description?: string;
  pluginId?: string;
  risk?: string;
  tags?: string[];
};

type CatalogGroup = {
  id: string;
  label: string;
  source: string;
  pluginId?: string;
  tools?: CatalogTool[];
};

type CatalogResult = { agentId?: string; groups?: CatalogGroup[] };

type ChannelStatusResult = {
  channelMeta?: Array<Record<string, unknown>>;
  channels?: Record<string, Record<string, unknown>>;
  channelAccounts?: Record<string, Array<Record<string, unknown>>>;
  channelDefaultAccountId?: Record<string, string>;
};

type ChannelCatalogResult = {
  chat?: Record<string, { accounts?: unknown[]; installed?: boolean; origin?: string }>;
};

type PluginInspectResult = {
  plugin?: {
    id?: string; name?: string; description?: string; version?: string;
    enabled?: boolean; status?: string; channelIds?: string[];
    configJsonSchema?: Record<string, unknown>;
  };
};

const CREDENTIAL_FIELD = /(secret|token|password|api.?key|app.?id|client.?id|private.?key|access.?key)/i;
const bridgeStartedAt = new Date().toISOString();
const inboundStats = {
  observed: 0,
  callbackAttempts: 0,
  callbackSucceeded: 0,
  callbackFailed: 0,
  lastObservedAt: null as string | null,
  lastCallbackSucceededAt: null as string | null,
  lastCallbackFailedAt: null as string | null,
  lastCallbackError: null as string | null,
};

function safeInboundError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message.replace(/[\r\n\t]+/g, " ").slice(0, 500);
}

function recordInboundFailure(error: unknown): void {
  inboundStats.callbackFailed += 1;
  inboundStats.lastCallbackFailedAt = new Date().toISOString();
  inboundStats.lastCallbackError = safeInboundError(error);
}

type IdempotencyEntry = { createdAt: number; result: Record<string, unknown> };
let idempotencyEntries: Record<string, IdempotencyEntry> | undefined;
let idempotencyLoad: Promise<Record<string, IdempotencyEntry>> | undefined;
let idempotencyPersist: Promise<void> = Promise.resolve();
const idempotencyInFlight = new Map<string, Promise<Record<string, unknown>>>();

function idempotencyPath(): string {
  return join(process.env.OPENCLAW_STATE_DIR || "/var/lib/openclaw", "agent-start-send-idempotency.json");
}

async function loadIdempotency(): Promise<Record<string, IdempotencyEntry>> {
  if (idempotencyEntries) return idempotencyEntries;
  if (!idempotencyLoad) idempotencyLoad = (async () => {
    try {
      const parsed = JSON.parse(await readFile(idempotencyPath(), "utf8")) as Record<string, IdempotencyEntry>;
      idempotencyEntries = parsed && typeof parsed === "object" ? parsed : {};
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (code !== "ENOENT" && !(error instanceof SyntaxError)) throw error;
      idempotencyEntries = {};
    }
    return idempotencyEntries;
  })();
  return idempotencyLoad;
}

function persistIdempotency(entries: Record<string, IdempotencyEntry>): Promise<void> {
  const ordered = Object.entries(entries).sort((a, b) => b[1].createdAt - a[1].createdAt).slice(0, 10_000);
  idempotencyEntries = Object.fromEntries(ordered);
  const snapshot = JSON.stringify(idempotencyEntries);
  const path = idempotencyPath();
  idempotencyPersist = idempotencyPersist.then(async () => {
    const temporary = `${path}.${process.pid}.tmp`;
    await mkdir(dirname(path), { recursive: true });
    await writeFile(temporary, snapshot, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, path);
  });
  return idempotencyPersist;
}

async function sendIdempotently(scope: string, key: string | undefined,
  operation: () => Promise<Record<string, unknown>>): Promise<Record<string, unknown>> {
  if (!key) return operation();
  const digest = createHash("sha256").update(`${scope}\0${key}`).digest("hex");
  const entries = await loadIdempotency();
  const prior = entries[digest];
  if (prior) return { ...prior.result, idempotencyReplay: true };
  const active = idempotencyInFlight.get(digest);
  if (active) return active;
  const task = operation().then(async (result) => {
    entries[digest] = { createdAt: Date.now(), result };
    await persistIdempotency(entries);
    return result;
  }).finally(() => idempotencyInFlight.delete(digest));
  idempotencyInFlight.set(digest, task);
  return task;
}

function splitChannelSchema(schema?: Record<string, unknown>): {
  credentialSchema: Record<string, unknown>; configurationSchema: Record<string, unknown>;
} {
  const defs = schema?.$defs && typeof schema.$defs === "object" ? schema.$defs as Record<string, unknown> : {};
  const account = defs.account && typeof defs.account === "object"
    ? defs.account as Record<string, unknown> : schema ?? {};
  const properties = account.properties && typeof account.properties === "object"
    ? account.properties as Record<string, unknown> : {};
  const credentialProperties: Record<string, unknown> = {};
  const configurationProperties: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(properties)) {
    if (CREDENTIAL_FIELD.test(key)) credentialProperties[key] = value;
    else if (key !== "enabled" && key !== "name") configurationProperties[key] = value;
  }
  const required = Array.isArray(account.required) ? account.required.map(String) : [];
  const inferredRequired = Object.keys(credentialProperties).filter((key) =>
    /^(appId|clientSecret|token|apiKey)$/i.test(key));
  const make = (selected: Record<string, unknown>, selectedRequired: string[]) => ({
    type: "object", properties: selected,
    ...(selectedRequired.length ? { required: selectedRequired } : {}),
    additionalProperties: false, ...(Object.keys(defs).length ? { $defs: defs } : {}),
  });
  return {
    credentialSchema: make(credentialProperties,
      [...new Set([...required, ...inferredRequired])].filter((key) => key in credentialProperties)),
    configurationSchema: make(configurationProperties, required.filter((key) => key in configurationProperties)),
  };
}

function json(res: ServerResponse, status: number, value: unknown): void {
  const body = JSON.stringify(value);
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Content-Length", Buffer.byteLength(body));
  res.end(body);
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += bytes.length;
    if (size > 1024 * 1024) throw new Error("request body exceeds 1 MiB");
    chunks.push(bytes);
  }
  if (chunks.length === 0) return {};
  const value: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("JSON request body must be an object");
  }
  return value as Record<string, unknown>;
}

function normalizeContent(output: unknown): { data: unknown; content: Record<string, unknown>[] } {
  if (!output || typeof output !== "object") return {
    data: output,
    content: typeof output === "string" ? [{ type: "TEXT", text: output }] : [],
  };
  const value = output as Record<string, unknown>;
  const rawContent = Array.isArray(value.content) ? value.content : [];
  const content = rawContent.map((item) => {
    if (!item || typeof item !== "object") return { type: "RESOURCE", text: String(item) };
    const block = item as Record<string, unknown>;
    const rawType = String(block.type ?? "RESOURCE").toUpperCase();
    const type = rawType === "IMAGE_URL" || rawType === "IMAGE_DATA" ? "IMAGE"
      : rawType === "FILE_URL" || rawType === "FILE_DATA" ? "FILE" : rawType;
    return { ...block, type };
  });
  return { data: value.structuredContent ?? value.data ?? output, content };
}

function channelAccountView(status: ChannelStatusResult, channelId: string,
                            accountId: string): Record<string, unknown> | undefined {
  const accounts = status.channelAccounts?.[channelId] ?? [];
  const raw = accounts.find((item) => String(item.accountId ?? item.id ?? "default") === accountId);
  if (!raw) return undefined;
  const channel = status.channels?.[channelId] ?? {};
  return {
    channelId,
    accountId,
    name: String(raw.name ?? raw.label ?? accountId),
    enabled: raw.enabled !== false,
    configured: raw.configured === true || channel.configured === true,
    running: raw.running === true || channel.running === true,
    connected: raw.connected === true || channel.connected === true,
    lastConnectedAt: raw.lastConnectedAt ?? channel.lastConnectedAt ?? null,
    lastError: raw.lastError ?? channel.lastError ?? null,
    metadata: {},
  };
}

function spawnOpenClaw(args: string[]) {
  const cliEntry = process.argv[1];
  return cliEntry
    ? spawn(process.execPath, [cliEntry, ...args], { stdio: ["ignore", "pipe", "pipe"], windowsHide: true })
    : spawn("openclaw", args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
}

function runOpenClaw(args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawnOpenClaw(args);
    let stderr = "";
    child.stderr.on("data", (chunk) => { stderr += String(chunk); });
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolve()
      : reject(new Error(stderr.trim() || `openclaw exited with code ${code}`)));
  });
}

function runOpenClawJson<T>(args: string[]): Promise<T> {
  return new Promise((resolve, reject) => {
    const child = spawnOpenClaw(args);
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += String(chunk); });
    child.stderr.on("data", (chunk) => { stderr += String(chunk); });
    child.once("error", reject);
    child.once("exit", (code) => {
      if (code !== 0) {
        reject(new Error(stderr.trim() || stdout.trim() || `openclaw exited with code ${code}`));
        return;
      }
      try { resolve(JSON.parse(stdout) as T); }
      catch { reject(new Error(`OpenClaw returned invalid JSON: ${stdout.slice(0, 500)}`)); }
    });
  });
}

function isInstallablePluginSource(source: string): boolean {
  if (source.startsWith("clawhub:")) {
    return /^clawhub:[a-z0-9][a-z0-9._/-]*$/i.test(source);
  }
  return /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/i.test(source);
}

function isPluginVersion(version: string): boolean {
  return /^[a-z0-9][a-z0-9._+~-]*$/i.test(version);
}

function scheduleGatewayRestart(): void {
  const timer = setTimeout(() => process.kill(process.pid, "SIGUSR1"), 250);
  timer.unref();
}

export default definePluginEntry({
  id: "agent-start-bridge",
  name: "Spring Agent Start Bridge",
  description: "Authenticated HTTP bridge exposing OpenClaw tools to spring-agent-start.",
  register(api) {
    const config = (api.pluginConfig ?? {}) as Partial<BridgeConfig>;
    const gatewayCall = <T>(method: string, params: Record<string, unknown>): Promise<T> => {
      const args = ["gateway", "call", method, "--json", "--params", JSON.stringify(params)];
      if (config.gatewayUrl?.trim()) args.push("--url", config.gatewayUrl.trim());
      const gatewayToken = config.gatewayToken?.trim() || process.env.OPENCLAW_GATEWAY_TOKEN?.trim();
      if (gatewayToken) args.push("--token", gatewayToken);
      return runOpenClawJson<T>(args);
    };

    api.on("before_dispatch", async (event, ctx) => {
      inboundStats.observed += 1;
      inboundStats.lastObservedAt = new Date().toISOString();
      const sink = config.eventSinkUrl?.trim();
      const channelId = ctx.channelId ?? event.channel;
      if (!sink || !channelId) return;
      const timeoutMs = Math.max(1000, Math.min(120000, config.eventSinkTimeoutMs ?? 60000));
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), timeoutMs);
      try {
        inboundStats.callbackAttempts += 1;
        const occurredAt = event.timestamp ? new Date(event.timestamp).toISOString() : new Date().toISOString();
        const messageId = createHash("sha256").update([
          channelId, ctx.accountId ?? "default", event.senderId ?? ctx.senderId ?? "",
          event.sessionKey ?? ctx.sessionKey ?? "", occurredAt, event.content,
        ].join("\u001f")).digest("hex");
        const response = await fetch(sink, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Agent-Start-Token": config.serviceToken?.trim()
              || process.env.AGENT_START_BRIDGE_TOKEN?.trim()
              || "",
          },
          body: JSON.stringify({
            provider: "openclaw",
            runtimeNodeId: config.runtimeNodeId?.trim()
              || process.env.AGENT_START_RUNTIME_NODE_ID?.trim()
              || "openclaw-default",
            channelId,
            accountId: ctx.accountId ?? "default",
            messageId,
            senderId: event.senderId ?? ctx.senderId ?? null,
            conversationId: ctx.conversationId ?? event.sessionKey ?? event.senderId ?? "unknown",
            content: event.content,
            timestamp: occurredAt,
            group: event.isGroup,
            metadata: {
              sessionKey: event.sessionKey ?? ctx.sessionKey,
              replyTargetId: event.isGroup
                ? (ctx.conversationId ?? event.sessionKey ?? ctx.sessionKey)
                : (event.senderId ?? ctx.senderId),
            },
          }),
          signal: controller.signal,
        });
        if (!response.ok) {
          recordInboundFailure(`HTTP ${response.status}`);
          api.logger.warn(`channel event sink returned ${response.status}`);
          return;
        }
        inboundStats.callbackSucceeded += 1;
        inboundStats.lastCallbackSucceededAt = new Date().toISOString();
        inboundStats.lastCallbackError = null;
        const result = await response.json() as { handled?: boolean; reply?: string; metadata?: { managed?: boolean } };
        if (result.metadata?.managed !== true && result.handled !== true) return;
        return { handled: true, ...(result.reply ? { text: result.reply } : {}) };
      } catch (error) {
        recordInboundFailure(error);
        api.logger.error(`channel event sink failed: ${String(error)}`);
        return;
      } finally {
        clearTimeout(timeout);
      }
    }, { priority: 100, timeoutMs: 120000 });

    api.registerHttpRoute({
      path: ROOT,
      match: "prefix",
      auth: "plugin",
      handler: async (req, res) => {
        const expected = config.serviceToken?.trim() || process.env.AGENT_START_BRIDGE_TOKEN?.trim();
        const supplied = req.headers["x-agent-start-token"];
        if (!expected || supplied !== expected) {
          json(res, 401, { code: "unauthorized", message: "invalid bridge service token" });
          return true;
        }

        try {
          const url = new URL(req.url ?? ROOT, "http://openclaw.internal");
          const path = url.pathname.slice(ROOT.length) || "/";

          if (req.method === "GET" && (path === "/health" || path === "/runtime")) {
            json(res, 200, {
              status: "UP",
              version: api.runtime.version,
              bridgeVersion: "0.1.0",
              startedAt: bridgeStartedAt,
              inbound: { ...inboundStats },
              capabilities: ["plugins.read", "plugins.configure", "plugins.install",
                "plugins.uninstall", "tools.read", "tools.invoke"],
            });
            return true;
          }

          if (req.method === "GET" && path === "/tools") {
            const catalog = await gatewayCall<CatalogResult>("tools.catalog", {
              agentId: url.searchParams.get("agentId") ?? "main",
              includePlugins: true,
            });
            const tools = (catalog.groups ?? [])
              .filter((group) => group.source === "plugin")
              .flatMap((group) => (group.tools ?? []).map((tool) => ({
                pluginId: tool.pluginId ?? group.pluginId ?? group.id.replace(/^plugin:/, ""),
                name: tool.id,
                label: tool.label ?? tool.id,
                description: tool.description ?? tool.label ?? tool.id,
                inputSchema: OPEN_OBJECT_SCHEMA,
                risk: tool.risk ?? "write",
                tags: tool.tags ?? [],
                metadata: { catalogGroup: group.id },
              })));
            json(res, 200, tools);
            return true;
          }

          if (req.method === "GET" && path === "/plugins") {
            const catalog = await gatewayCall<CatalogResult>("tools.catalog", {
              agentId: url.searchParams.get("agentId") ?? "main",
              includePlugins: true,
            });
            const entries = api.runtime.config.current().plugins?.entries ?? {};
            const plugins = (catalog.groups ?? []).filter((group) => group.source === "plugin")
              .map((group) => {
                const id = group.pluginId ?? group.id.replace(/^plugin:/, "");
                const entry = entries[id] as { enabled?: boolean } | undefined;
                return {
                  id,
                  name: group.label || id,
                  version: "unknown",
                  description: `OpenClaw plugin providing ${(group.tools ?? []).length} tool(s)`,
                  enabled: entry?.enabled !== false,
                  license: null,
                  configSchema: null,
                  metadata: { toolCount: (group.tools ?? []).length },
                };
              });
            json(res, 200, plugins);
            return true;
          }

          if (req.method === "GET" && path === "/channels") {
            const [status, catalog] = await Promise.all([
              gatewayCall<ChannelStatusResult>("channels.status", {}),
              runOpenClawJson<ChannelCatalogResult>(["channels", "list", "--all", "--json"]),
            ]);
            const meta = new Map((status.channelMeta ?? []).map((item) => [String(item.id ?? ""), item]));
            const channels = await Promise.all(Object.entries(catalog.chat ?? {}).map(async ([id, entry]) => {
              const item = meta.get(id) ?? {};
              let plugin: PluginInspectResult["plugin"];
              if (entry.installed) {
                try { plugin = (await runOpenClawJson<PluginInspectResult>(["plugins", "inspect", id, "--json"])).plugin; }
                catch { plugin = undefined; }
              }
              const schemas = splitChannelSchema(plugin?.configJsonSchema);
              const runtime = status.channels?.[id] ?? {};
              return {
                id,
                label: String(plugin?.name ?? item.label ?? id),
                description: String(plugin?.description ?? item.detailLabel ?? item.label ?? id),
                version: plugin?.version ?? null,
                installed: entry.installed === true,
                enabled: plugin?.enabled === true,
                runtimeStatus: runtime.connected === true ? "ONLINE"
                  : runtime.running === true ? "RUNNING" : entry.installed ? "OFFLINE" : "NOT_INSTALLED",
                credentialSchema: JSON.stringify(schemas.credentialSchema),
                configSchema: JSON.stringify(schemas.configurationSchema),
                uiSchema: {},
                // `message send` confirms that the channel adapter accepted the outbound
                // operation. It does not prove that the remote QQ/client received or read it.
                // Keep these capabilities explicit so control planes never promote SENT to
                // DELIVERED merely because a platform message id was returned.
                capabilities: {
                  multiAccount: true,
                  inbound: true,
                  outbound: true,
                  deliveryReceipts: false,
                  readReceipts: false,
                },
                metadata: {
                  ...(entry.origin ? { origin: entry.origin } : {}),
                  ...(item.systemImage ? { systemImage: item.systemImage } : {}),
                  configured: runtime.configured === true,
                  ...(plugin?.status ? { pluginStatus: plugin.status } : {}),
                  packageSpec: `@openclaw/${id}`,
                },
              };
            }));
            json(res, 200, channels);
            return true;
          }

          const channelAccounts = path.match(/^\/channels\/([^/]+)\/accounts$/);
          if (req.method === "GET" && channelAccounts) {
            const channelId = decodeURIComponent(channelAccounts[1]);
            const status = await gatewayCall<ChannelStatusResult>("channels.status", {});
            const accounts = (status.channelAccounts?.[channelId] ?? []).map((raw) => {
              const accountId = String(raw.accountId ?? raw.id ?? "default");
              return channelAccountView(status, channelId, accountId);
            }).filter((item): item is Record<string, unknown> => item !== undefined);
            json(res, 200, accounts);
            return true;
          }

          const channelAccountTest = path.match(/^\/channels\/([^/]+)\/accounts\/([^/]+)\/test$/);
          if (req.method === "POST" && channelAccountTest) {
            const channelId = decodeURIComponent(channelAccountTest[1]);
            const accountId = decodeURIComponent(channelAccountTest[2]);
            const status = await gatewayCall<ChannelStatusResult>("channels.status", { probe: true });
            const account = channelAccountView(status, channelId, accountId);
            if (!account) {
              json(res, 404, { code: "channel_account_not_found", message: "channel account not found" });
              return true;
            }
            json(res, 200, account);
            return true;
          }

          const channelAccount = path.match(/^\/channels\/([^/]+)\/accounts\/([^/]+)$/);
          const channelSend = path.match(/^\/channels\/([^/]+)\/accounts\/([^/]+)\/send$/);
          if (req.method === "POST" && channelSend) {
            const channelId = decodeURIComponent(channelSend[1]);
            const accountId = decodeURIComponent(channelSend[2]);
            const body = await readJson(req);
            const attachments = Array.isArray(body.attachments)
              ? body.attachments.filter((item): item is Record<string, unknown> => !!item && typeof item === "object" && !Array.isArray(item))
              : [];
            if (typeof body.targetId !== "string" || !body.targetId.trim()
                || (typeof body.content !== "string" && attachments.length === 0)) {
              json(res, 400, { code: "invalid_message", message: "targetId and content or attachments are required" }); return true;
            }
            const targetId = body.targetId.trim();
            const content = typeof body.content === "string" ? body.content : "";
            const key = typeof body.idempotencyKey === "string" && body.idempotencyKey.trim()
              ? body.idempotencyKey.trim() : undefined;
            const sent = await sendIdempotently(`${channelId}:${accountId}`, key, async () => {
              const deliveries: Record<string, unknown>[] = [];
              const media = attachments.map((item) => item.url).filter((url): url is string => typeof url === "string" && !!url.trim());
              const count = Math.max(1, media.length);
              for (let index = 0; index < count; index++) {
                const args = ["message", "send", "--channel", channelId,
                  "--account", accountId, "--target", targetId];
                if (index === 0 && content) args.push("--message", content);
                if (media[index]) args.push("--media", media[index]);
                if (index === 0 && body.contentPayload && typeof body.contentPayload === "object"
                    && !Array.isArray(body.contentPayload) && Object.keys(body.contentPayload as object).length > 0) {
                  args.push("--presentation", JSON.stringify(body.contentPayload));
                }
                args.push("--json");
                deliveries.push(await runOpenClawJson<Record<string, unknown>>(args));
              }
              return deliveries.length === 1 ? deliveries[0] : { deliveries };
            });
            json(res, 200, sent); return true;
          }
          if (req.method === "PUT" && channelAccount) {
            const channelId = decodeURIComponent(channelAccount[1]);
            const accountId = decodeURIComponent(channelAccount[2]);
            const body = await readJson(req);
            const values = body.config;
            if (!values || typeof values !== "object" || Array.isArray(values)) {
              json(res, 400, { code: "invalid_config", message: "config must be an object" });
              return true;
            }
            await api.runtime.config.mutateConfigFile({
              afterWrite: { mode: "none", reason: "channel account response must complete before restart" },
              mutate(draft) {
                const root = draft as unknown as Record<string, unknown>;
                const channels = (root.channels ??= {}) as Record<string, unknown>;
                const channel = (channels[channelId] ??= {}) as Record<string, unknown>;
                channel.enabled = true;
                const accounts = (channel.accounts ??= {}) as Record<string, unknown>;
                accounts[accountId] = {
                  ...(accounts[accountId] as Record<string, unknown> | undefined ?? {}),
                  ...(values as Record<string, unknown>),
                  enabled: body.enabled !== false,
                  ...(typeof body.name === "string" && body.name.trim() ? { name: body.name.trim() } : {}),
                };
              },
            });
            json(res, 202, {
              channelId, accountId, name: typeof body.name === "string" ? body.name : accountId,
              enabled: body.enabled !== false, configured: true, running: false, connected: false,
              lastConnectedAt: null, lastError: null, metadata: { pendingReload: true },
            });
            scheduleGatewayRestart();
            return true;
          }

          if (req.method === "DELETE" && channelAccount) {
            const channelId = decodeURIComponent(channelAccount[1]);
            const accountId = decodeURIComponent(channelAccount[2]);
            await api.runtime.config.mutateConfigFile({
              afterWrite: { mode: "none", reason: "channel account response must complete before restart" },
              mutate(draft) {
                const root = draft as unknown as Record<string, unknown>;
                const channels = root.channels as Record<string, unknown> | undefined;
                const channel = channels?.[channelId] as Record<string, unknown> | undefined;
                const accounts = channel?.accounts as Record<string, unknown> | undefined;
                if (accounts) delete accounts[accountId];
              },
            });
            res.statusCode = 204; res.end();
            scheduleGatewayRestart();
            return true;
          }

          const invoke = path.match(/^\/tools\/([^/]+)\/invoke$/);
          if (req.method === "POST" && invoke) {
            const body = await readJson(req);
            const toolName = decodeURIComponent(invoke[1]);
            const result = await gatewayCall<Record<string, unknown>>("tools.invoke", {
              name: toolName,
              agentId: typeof body.agentId === "string" ? body.agentId : undefined,
              args: body.arguments && typeof body.arguments === "object" ? body.arguments : {},
              idempotencyKey: typeof body.requestId === "string" ? body.requestId : undefined,
            });
            if (result.ok === false) {
              const error = (result.error ?? {}) as Record<string, unknown>;
              json(res, 200, { success: false, toolName, error: {
                code: String(error.code ?? "openclaw_error"),
                message: String(error.message ?? "OpenClaw tool invocation failed"),
                retryable: false,
              }});
              return true;
            }
            const normalized = normalizeContent(result.output);
            json(res, 200, { success: true, toolName, ...normalized,
              metadata: { source: result.source ?? "plugin" } });
            return true;
          }

          if (req.method === "POST" && path === "/plugins/install") {
            if (config.allowInstall !== true) {
              json(res, 403, { code: "install_disabled", message: "plugin installation is disabled" });
              return true;
            }
            const body = await readJson(req);
            const sourceType = String(body.sourceType ?? "npm").trim().toLowerCase();
            const requestedSource = String(body.source ?? "").trim();
            if (sourceType !== "npm" && sourceType !== "clawhub") {
              json(res, 400, { code: "invalid_source_type",
                message: "sourceType must be npm or clawhub" });
              return true;
            }
            const source = sourceType === "clawhub" && !requestedSource.startsWith("clawhub:")
              ? `clawhub:${requestedSource}` : requestedSource;
            const version = body.version == null ? "" : String(body.version).trim();
            if (!isInstallablePluginSource(source) || (version && !isPluginVersion(version))) {
              json(res, 400, { code: "invalid_plugin_source",
                message: "source must be an npm package name or clawhub identifier" });
              return true;
            }
            const spec = version ? `${source}@${version}` : source;
            const installArgs = ["plugins", "install", spec];
            if (source.startsWith("clawhub:")) installArgs.push("--acknowledge-clawhub-risk");
            await runOpenClaw(installArgs);
            const pluginId = source.replace(/^clawhub:/, "").split("/").pop()?.replace(/@[^@]+$/, "") ?? source;
            await api.runtime.config.mutateConfigFile({
              afterWrite: { mode: "auto" },
              mutate(draft) {
                draft.plugins ??= {}; draft.plugins.entries ??= {};
                draft.plugins.entries[pluginId] = { ...(draft.plugins.entries[pluginId] ?? {}), enabled: true };
                const allow = new Set(draft.plugins.allow ?? []); allow.add("agent-start-bridge"); allow.add(pluginId);
                draft.plugins.allow = [...allow];
              },
            });
            json(res, 202, { id: source, name: source, version: body.version ?? "unknown",
              description: "Installed; Gateway reload may be required", enabled: true,
              license: null, configSchema: null, metadata: { pendingReload: true } });
            return true;
          }

          const state = path.match(/^\/plugins\/([^/]+)\/(enable|disable)$/);
          if (req.method === "POST" && state) {
            const id = decodeURIComponent(state[1]);
            await api.runtime.config.mutateConfigFile({
              afterWrite: { mode: "auto" },
              mutate(draft) {
                draft.plugins ??= {};
                draft.plugins.entries ??= {};
                draft.plugins.entries[id] = {
                  ...(draft.plugins.entries[id] ?? {}),
                  enabled: state[2] === "enable",
                };
              },
            });
            res.statusCode = 204; res.end(); return true;
          }

          const pluginConfig = path.match(/^\/plugins\/([^/]+)\/config$/);
          if (req.method === "PUT" && pluginConfig) {
            const id = decodeURIComponent(pluginConfig[1]);
            const body = await readJson(req);
            const values = body.config;
            if (!values || typeof values !== "object" || Array.isArray(values)) {
              json(res, 400, { code: "invalid_config", message: "config must be an object" });
              return true;
            }
            await api.runtime.config.mutateConfigFile({
              afterWrite: { mode: "auto" },
              mutate(draft) {
                draft.plugins ??= {};
                draft.plugins.entries ??= {};
                draft.plugins.entries[id] = {
                  ...(draft.plugins.entries[id] ?? {}),
                  config: values as Record<string, unknown>,
                };
              },
            });
            json(res, 200, { id, name: id, version: "unknown",
              description: "OpenClaw plugin configuration updated", enabled: true,
              license: null, configSchema: null, metadata: { pendingReload: true } });
            return true;
          }

          const uninstall = path.match(/^\/plugins\/([^/]+)$/);
          if (req.method === "DELETE" && uninstall) {
            if (config.allowInstall !== true) {
              json(res, 403, { code: "uninstall_disabled", message: "plugin lifecycle changes are disabled" });
              return true;
            }
            const id = decodeURIComponent(uninstall[1]);
            await runOpenClaw(["plugins", "uninstall", id, "--force"]);
            res.statusCode = 204; res.end(); return true;
          }

          json(res, 404, { code: "not_found", message: "bridge route not found" });
          return true;
        } catch (error) {
          api.logger.error(`agent-start bridge request failed: ${String(error)}`);
          json(res, 500, { code: "bridge_error", message: error instanceof Error ? error.message : String(error) });
          return true;
        }
      },
    });
  },
});
