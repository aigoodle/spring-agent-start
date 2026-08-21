package io.github.aigoodle.connector.hermes;

import io.github.aigoodle.connector.channel.*;
import java.time.Instant;
import java.util.*;

/** Maps Agent Start channel accounts to isolated Hermes Gateway profiles. */
public class HermesChannelRuntimeProvider implements ChannelRuntimeProvider {
    private static final String PROVIDER = "hermes";
    private static final String DOC_ROOT = "https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging";
    private static final List<Platform> CATALOG = List.of(
            p("telegram", "Telegram", "telegram", "token"), p("discord", "Discord", "discord", "token"),
            p("slack", "Slack", "slack", "token"), p("whatsapp", "WhatsApp", "whatsapp", "token"),
            p("signal", "Signal", "signal", "token"), p("email", "Email", "email", "password"),
            p("mattermost", "Mattermost", "mattermost", "token"), p("matrix", "Matrix", "matrix", "accessToken"),
            p("dingtalk", "DingTalk", "dingtalk", "clientSecret"), p("feishu", "Feishu / Lark", "feishu", "appSecret"),
            p("wecom", "WeCom", "wecom", "secret"), p("weixin", "Weixin", "weixin", "token"),
            p("qqbot", "QQ Bot", "qq", "clientSecret"), p("yuanbao", "Yuanbao", "yuanbao", "token"),
            p("teams", "Microsoft Teams", "msteams", "clientSecret"), p("line", "LINE", "line", "channelAccessToken"),
            p("sms", "SMS", "sms", "token"), p("google_chat", "Google Chat", "googlechat", "credentialsJson"),
            p("bluebubbles", "BlueBubbles", "imessage", "password"), p("ntfy", "ntfy", "ntfy", "token")
    );
    private final HermesDashboardClient client;
    private final HermesBridgeClient bridge;
    public HermesChannelRuntimeProvider(HermesDashboardClient client, HermesBridgeClient bridge) {
        this.client = client; this.bridge = bridge;
    }
    @Override public String type() { return PROVIDER; }

    @Override public List<ChannelDefinition> discoverChannels() {
        Map<String, Object> status = client.platforms(null);
        boolean bridgeReady = bridge.healthy();
        return CATALOG.stream().map(platform -> definition(platform, platformState(status, platform.id()), bridgeReady)).toList();
    }
    @Override public List<ChannelAccount> accounts(String channelId) {
        Object rawStates = bridge.status().get("profileStates");
        if (!(rawStates instanceof List<?> states)) return List.of();
        return states.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(state -> channelId.equals(string(state.get("platform"))))
                .map(state -> runtimeAccount(channelId, state))
                .toList();
    }
    @Override public ChannelAccount saveAccount(SaveChannelAccountRequest request) {
        Map<String, Object> values = new LinkedHashMap<>(request.configuration());
        // The trusted connection id owns the shared Hermes namespace. Never let tenant configuration
        // select another tenant's profile before the database ownership constraint is checked.
        values.remove("profile");
        String profile = requiredProfile(request.accountId());
        Map<String, Object> dashboardBody = dashboardConfiguration(request.channelId(), profile, request.enabled(), values);
        Map<String, Object> response = client.savePlatform(request.channelId(), profile, dashboardBody);
        return account(request.channelId(), profile, request.name(), request.enabled(), response);
    }
    @Override public ChannelAccount testAccount(String channelId, String accountId) {
        client.health(); Map<String, Object> status = client.platforms(accountId);
        return account(channelId, accountId, channelId + " / " + accountId, true, platformState(status, channelId));
    }
    @Override public void deleteAccount(String channelId, String accountId) {
        client.savePlatform(channelId, accountId, Map.of("enabled", false, "profile", accountId,
                "env", Map.of(), "clear_env", List.of()));
    }
    @Override public ChannelSendResult sendWithResult(ChannelOutboundMessage message) {
        if (!"qqbot".equals(message.channelId()))
            throw new UnsupportedOperationException("Agent Start outbound bridge is not installed for Hermes " + message.channelId());
        return bridge.send(message);
    }

    private static ChannelDefinition definition(Platform p, Map<String, Object> state, boolean bridgeReady) {
        boolean enabled = bool(state.get("enabled")); boolean running = bool(state.get("running")) || bool(state.get("connected"));
        boolean agentStartBridge = bridgeReady && "qqbot".equals(p.id());
        Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("platformId", p.platformId());
        metadata.put("publisher", "Nous Research"); metadata.put("verificationStatus", "RUNTIME_OFFICIAL");
        metadata.put("homepageUrl", DOC_ROOT + "/" + p.id() + ".md");
        metadata.put("sourceUrl", "https://github.com/NousResearch/hermes-agent/tree/main/plugins/platforms/" + p.id());
        metadata.put("accountModel", "PROFILE");
        metadata.put("routingMode", agentStartBridge ? "AGENT_START" : "HERMES_NATIVE");
        metadata.put("routingNotice", agentStartBridge ? "Messages are routed through the authenticated Agent Start bridge."
                : "The Dashboard API manages Hermes profiles; Agent Start message routing requires the Hermes bridge extension.");
        return new ChannelDefinition(PROVIDER, p.id(), p.label(), "Hermes " + p.label() + " platform adapter", null,
                true, enabled, running ? "ONLINE" : enabled ? "OFFLINE" : "DISABLED",
                credentialSchema(p), configSchema(), Map.of(),
                Map.of("multiAccount", false, "multiProfile", true, "configuration", true,
                        "agentStartInbound", agentStartBridge, "agentStartOutbound", agentStartBridge,
                        // The bridge can acknowledge Hermes adapter acceptance, but Hermes/QQ does not
                        // currently expose a terminal delivery or read receipt to Agent Start.
                        "deliveryReceipts", false, "readReceipts", false), metadata);
    }
    private static ChannelAccount account(String channel, String profile, String name, boolean enabled, Map<String, Object> state) {
        boolean connected = bool(state.get("connected")) || bool(state.get("running"));
        return new ChannelAccount(PROVIDER, channel, profile, name, enabled, true, enabled, connected,
                connected ? Instant.now() : null, string(state.get("error")), Map.of("profile", profile));
    }
    private static ChannelAccount runtimeAccount(String channel, Map<?, ?> state) {
        String profile = requiredProfile(state.get("profile"));
        boolean reachable = bool(state.get("reachable"));
        boolean connected = reachable && bool(state.get("connected"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("profile", profile);
        metadata.put("reachable", reachable);
        Object pending = state.get("pendingInboundCallbacks");
        if (pending != null) metadata.put("pendingInboundCallbacks", pending);
        Object callbackWorkerRunning = state.get("callbackWorkerRunning");
        if (callbackWorkerRunning != null) metadata.put("callbackWorkerRunning", callbackWorkerRunning);
        Object callbackWorkerFailures = state.get("callbackWorkerFailures");
        if (callbackWorkerFailures != null) metadata.put("callbackWorkerFailures", callbackWorkerFailures);
        Object callbackWorkerError = state.get("callbackWorkerError");
        if (callbackWorkerError != null) metadata.put("callbackWorkerError", callbackWorkerError);
        return new ChannelAccount(PROVIDER, channel, profile, profile, true, true, reachable, connected,
                connected ? Instant.now() : null, string(state.get("error")), metadata);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> platformState(Map<String, Object> root, String id) {
        Object platforms = root.getOrDefault("platforms", root);
        if (platforms instanceof Map<?, ?> map) {
            Object state = map.get(id); if (state instanceof Map<?, ?> value) return (Map<String, Object>) value;
        }
        if (platforms instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> map
                && id.equals(String.valueOf(map.get("id")))) return (Map<String, Object>) map;
        return Map.of();
    }
    private static String credentialSchema(Platform platform) {
        if ("qqbot".equals(platform.id())) return "{\"type\":\"object\",\"properties\":{" +
                "\"appId\":{\"type\":\"string\",\"title\":\"QQ App ID\"}," +
                "\"clientSecret\":{\"type\":\"string\",\"format\":\"password\",\"title\":\"QQ Client Secret\"}," +
                "\"allowedUsers\":{\"type\":\"string\",\"title\":\"Allowed QQ users\"}}," +
                "\"required\":[\"appId\",\"clientSecret\"],\"additionalProperties\":false}";
        return "{\"type\":\"object\",\"properties\":{\"" + platform.credential() +
                "\":{\"type\":\"string\",\"format\":\"password\"}},\"required\":[\"" +
                platform.credential() + "\"],\"additionalProperties\":true}";
    }
    private static Map<String, Object> dashboardConfiguration(String channelId, String profile, boolean enabled,
                                                               Map<String, Object> values) {
        if (!"qqbot".equals(channelId)) {
            return Map.of("enabled", enabled, "profile", profile, "env", values, "clear_env", List.of());
        }
        Map<String, String> env = new LinkedHashMap<>();
        putEnv(env, "QQ_APP_ID", values.get("appId"));
        putEnv(env, "QQ_CLIENT_SECRET", values.get("clientSecret"));
        putEnv(env, "QQ_ALLOWED_USERS", values.get("allowedUsers"));
        if (!env.containsKey("QQ_APP_ID") || !env.containsKey("QQ_CLIENT_SECRET"))
            throw new IllegalArgumentException("QQBot appId and clientSecret are required");
        return Map.of("enabled", enabled, "profile", profile, "env", env, "clear_env", List.of());
    }
    private static void putEnv(Map<String, String> env, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) env.put(key, String.valueOf(value).trim());
    }
    private static String configSchema() { return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}"; }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)); }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static String requiredProfile(Object value) { if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException("Hermes profile is required"); return String.valueOf(value).trim(); }
    private static Platform p(String id, String label, String platform, String credential) { return new Platform(id, label, platform, credential); }
    private record Platform(String id, String label, String platformId, String credential) {}
}
