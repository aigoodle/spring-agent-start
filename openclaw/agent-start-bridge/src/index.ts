import { spawn } from "node:child_process";
import type { IncomingMessage, ServerResponse } from "node:http";
import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";

const ROOT = "/agent-start-bridge/v1";
const OPEN_OBJECT_SCHEMA = JSON.stringify({
  type: "object",
  properties: {},
  additionalProperties: true,
});

type BridgeConfig = {
  serviceToken: string;
  gatewayUrl?: string;
  gatewayToken?: string;
  allowInstall?: boolean;
  allowedPackagePrefixes?: string[];
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
            const source = String(body.source ?? "").trim();
            const prefixes = config.allowedPackagePrefixes ?? ["@openclaw/", "@aigoodle/", "clawhub:"];
            if (!source || !prefixes.some((prefix) => source.startsWith(prefix))) {
              json(res, 403, { code: "source_not_allowed", message: "plugin source is not allowlisted" });
              return true;
            }
            const spec = body.version ? `${source}@${String(body.version)}` : source;
            const installArgs = ["plugins", "install", spec];
            if (source.startsWith("clawhub:")) installArgs.push("--acknowledge-clawhub-risk");
            await runOpenClaw(installArgs);
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
