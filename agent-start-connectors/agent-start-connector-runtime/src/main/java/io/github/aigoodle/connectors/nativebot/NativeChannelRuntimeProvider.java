package io.github.aigoodle.connectors.nativebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aigoodle.connector.channel.*;
import io.github.aigoodle.connectors.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bridges the lightweight connector SPI into the existing durable channel runtime. */
public final class NativeChannelRuntimeProvider implements ChannelRuntimeProvider {
  public static final String PROVIDER = "native";
  private static final Logger log = LoggerFactory.getLogger(NativeChannelRuntimeProvider.class);

  private record Account(
      String name,
      boolean enabled,
      NativeChannelConnector<Object> connector,
      Object config,
      Instant updatedAt) {}

  private final Map<String, NativeChannelConnector<?>> connectors;
  private final Map<String, Account> accounts = new ConcurrentHashMap<>();
  private final Map<String, ChannelSession> sessions = new ConcurrentHashMap<>();
  private final ObjectMapper json;
  private final NativeAccountStore store;
  private final InboundMessageSink sink;

  public NativeChannelRuntimeProvider(List<NativeChannelConnector<?>> values, ObjectMapper json) {
    this(values, json, null, null);
  }

  public NativeChannelRuntimeProvider(
      List<NativeChannelConnector<?>> values,
      ObjectMapper json,
      NativeAccountStore store,
      InboundMessageSink sink) {
    Map<String, NativeChannelConnector<?>> m = new LinkedHashMap<>();
    for (var c : values)
      if (m.putIfAbsent(c.id(), c) != null)
        throw new IllegalArgumentException("Duplicate native connector: " + c.id());
    connectors = Map.copyOf(m);
    this.json = json;
    this.store = store;
    this.sink = sink;
  }

  public String type() {
    return PROVIDER;
  }

  public List<ChannelDefinition> discoverChannels() {
    return connectors.values().stream()
        .map(
            c ->
                new ChannelDefinition(
                    PROVIDER,
                    c.id(),
                    c.descriptor().name(),
                    c.descriptor().description(),
                    c.descriptor().version(),
                    true,
                    true,
                    "UP",
                    write(c.credentialSchema()),
                    write(c.configurationSchema()),
                    Map.of(),
                    capabilities(c.descriptor().capabilities()),
                    c.descriptor().metadata()))
        .toList();
  }

  public List<ChannelAccount> accounts(String channelId) {
    return accounts.entrySet().stream()
        .filter(e -> e.getKey().startsWith(channelId + ":"))
        .map(e -> view(channelId, e.getKey().substring(channelId.length() + 1), e.getValue()))
        .toList();
  }

  /** Restores persisted streaming accounts and starts their sessions after application startup. */
  public void restoreEnabledAccounts() {
    if (store == null) return;
    for (NativeAccountStore.SavedAccount saved : store.findEnabled()) {
      String accountKey = key(saved.channelId(), saved.accountId());
      if (accounts.containsKey(accountKey)) continue;
      try {
        Account account = restore(saved.channelId(), saved.accountId(), saved.account());
        accounts.putIfAbsent(accountKey, account);
      } catch (RuntimeException e) {
        log.error(
            "Failed to restore native channel account {}/{}",
            saved.channelId(),
            saved.accountId(),
            e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  public ChannelAccount saveAccount(SaveChannelAccountRequest r) {
    NativeChannelConnector<Object> c = (NativeChannelConnector<Object>) require(r.channelId());
    Map<String, Object> raw = new LinkedHashMap<>(r.configuration());
    raw.put("accountId", r.accountId());
    validateRequired(c.credentialSchema(), raw);
    validateRequired(c.configurationSchema(), raw);
    Object cfg = json.convertValue(raw, c.configType());
    Account a = new Account(r.name(), r.enabled(), c, cfg, Instant.now());
    if (r.enabled()) {
      ConnectionTestResult t = c.test(cfg);
      if (!t.success()) throw new ChannelException(t.code(), t.message(), false);
    }
    accounts.put(key(r.channelId(), r.accountId()), a);
    startSession(r.channelId(), r.accountId(), a);
    return view(r.channelId(), r.accountId(), a);
  }

  public ChannelAccount testAccount(String channelId, String accountId) {
    Account a = requireAccount(channelId, accountId);
    ConnectionTestResult t = a.connector.test(a.config);
    ChannelSession session = sessions.get(key(channelId, accountId));
    boolean connected = session != null && session.connected();
    Map<String, Object> metadata = new LinkedHashMap<>(t.metadata());
    metadata.put("transport", transport(a, session));
    metadata.put("credentialsValid", t.success());
    return new ChannelAccount(
        PROVIDER,
        channelId,
        accountId,
        a.name,
        a.enabled,
        true,
        a.enabled,
        connected,
        connected ? Instant.now() : null,
        t.success() ? null : t.message(),
        Map.copyOf(metadata));
  }

  public void deleteAccount(String channelId, String accountId) {
    accounts.remove(key(channelId, accountId));
    ChannelSession session = sessions.remove(key(channelId, accountId));
    if (session != null) session.close();
  }

  public ChannelSendResult sendWithResult(ChannelOutboundMessage old) {
    Account a = requireAccount(old.channelId(), old.accountId());
    List<MessageContent> content = new ArrayList<>();
    if (old.content() != null && !old.content().isBlank())
      content.add(MessageContent.text(old.content()));
    old.attachments()
        .forEach(
            x ->
                content.add(
                    new MessageContent(
                        MessageType.valueOf(x.type()),
                        null,
                        x.url(),
                        x.name(),
                        x.mimeType(),
                        x.size(),
                        x.metadata())));
    Map<String, Object> metadata = new LinkedHashMap<>(old.metadata());
    metadata.putIfAbsent(
        "conversationType", old.contentPayload().getOrDefault("conversationType", "C2C"));
    String replyTo =
        metadata.get("replyToPlatformMessageId") == null
            ? null
            : String.valueOf(metadata.get("replyToPlatformMessageId"));
    SendResult r =
        a.connector.send(
            a.config,
            new OutboundMessage(
                String.valueOf(old.metadata().get("idempotencyKey")),
                old.conversationId(),
                old.targetId(),
                replyTo,
                content,
                metadata));
    return new ChannelSendResult(r.platformMessageId(), r.metadata());
  }

  public void send(ChannelOutboundMessage m) {
    sendWithResult(m);
  }

  public InboundMessage parse(
      String channelId, String accountId, Map<String, String> h, Map<String, Object> p) {
    Account a = requireAccount(channelId, accountId);
    return a.connector.parse(a.config, h, p);
  }

  public Object challenge(
      String channelId, String accountId, Map<String, String> h, Map<String, Object> p) {
    Account a = requireAccount(channelId, accountId);
    return a.connector.challenge(a.config, h, p);
  }

  private NativeChannelConnector<?> require(String id) {
    NativeChannelConnector<?> c = connectors.get(id);
    if (c == null) throw new IllegalArgumentException("Unknown native channel: " + id);
    return c;
  }

  private Account requireAccount(String c, String a) {
    Account v = accounts.get(key(c, a));
    if (v == null && store != null)
      v = store.find(c, a).map(saved -> restore(c, a, saved)).orElse(null);
    if (v == null)
      throw new IllegalArgumentException("Unknown native channel account: " + c + "/" + a);
    accounts.putIfAbsent(key(c, a), v);
    return v;
  }

  @SuppressWarnings("unchecked")
  private Account restore(String channel, String accountId, NativeAccountStore.Saved saved) {
    NativeChannelConnector<Object> connector = (NativeChannelConnector<Object>) require(channel);
    Object config = json.convertValue(saved.configuration(), connector.configType());
    Account account = new Account(saved.name(), saved.enabled(), connector, config, Instant.now());
    startSession(channel, accountId, account);
    return account;
  }

  private void startSession(String channel, String accountId, Account account) {
    ChannelSession old = sessions.remove(key(channel, accountId));
    if (old != null) old.close();
    if (account.enabled && sink != null && account.connector.descriptor().capabilities().streaming()) {
      ChannelSession session = account.connector.connect(account.config, sink);
      if (session != null) sessions.put(key(channel, accountId), session);
    }
  }

  private static String key(String c, String a) {
    return c + ":" + a;
  }

  private ChannelAccount view(String c, String id, Account a) {
    ChannelSession session = sessions.get(key(c, id));
    boolean connected = session != null && session.connected();
    return new ChannelAccount(
        PROVIDER,
        c,
        id,
        a.name,
        a.enabled,
        true,
        a.enabled,
        connected,
        connected ? a.updatedAt : null,
        null,
        Map.of("transport", transport(a, session), "credentialsValid", a.enabled));
  }

  private static String transport(Account account, ChannelSession session) {
    ChannelCapabilities capabilities = account.connector.descriptor().capabilities();
    if (capabilities.streaming() && capabilities.webhook() && session == null) return "webhook";
    Object value = account.connector.descriptor().metadata().get("transport");
    return value == null
        ? (capabilities.streaming() ? "stream" : "webhook")
        : String.valueOf(value);
  }

  private String write(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static void validateRequired(Map<String, Object> schema, Map<String, Object> values) {
    Object required = schema == null ? null : schema.get("required");
    if (!(required instanceof Collection<?> fields)) return;
    List<String> missing =
        fields.stream()
            .map(String::valueOf)
            .filter(
                field -> {
                  Object value = values.get(field);
                  return value == null || (value instanceof String text && text.isBlank());
                })
            .toList();
    if (!missing.isEmpty()) {
      throw new ChannelException(
          "invalid_configuration", "Missing required fields: " + String.join(", ", missing), false);
    }
  }

  private static Map<String, Object> capabilities(ChannelCapabilities c) {
    return Map.of(
        "inbound",
        c.inbound().stream().map(Enum::name).toList(),
        "outbound",
        c.outbound().stream().map(Enum::name).toList(),
        "webhook",
        c.webhook(),
        "streaming",
        c.streaming(),
        "groupChat",
        c.groupChat(),
        "deliveryReceipts",
        c.deliveryReceipts());
  }
}
