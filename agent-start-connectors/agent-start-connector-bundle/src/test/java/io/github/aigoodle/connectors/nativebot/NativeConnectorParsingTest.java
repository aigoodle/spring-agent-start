package io.github.aigoodle.connectors.nativebot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aigoodle.connectors.dingtalk.DingTalkConnector;
import io.github.aigoodle.connectors.email.EmailConnector;
import io.github.aigoodle.connectors.feishu.FeishuConnector;
import io.github.aigoodle.connectors.qqbot.QQBotConnector;
import io.github.aigoodle.connectors.webhook.WebhookConnector;
import io.github.aigoodle.connectors.wecom.WeComConnector;
import io.github.aigoodle.connector.channel.ChannelDefinition;
import io.github.aigoodle.connectors.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeConnectorParsingTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void qqGroupKeepsGroupReplyTargetAndVerifiesNativeSignature() throws Exception {
    QQBotConnector c = new QQBotConnector(json);
    QQBotConnector.Config cfg = new QQBotConnector.Config("a", "secret", "", "acc");
    Map<String, Object> payload =
        Map.of(
            "d",
            Map.of(
                "id",
                "m1",
                "group_openid",
                "g1",
                "content",
                "hello",
                "author",
                Map.of("member_openid", "u1")));
    String raw = json.writeValueAsString(payload), timestamp = "1730000000";
    @SuppressWarnings("unchecked")
    Map<String, Object> signed =
        (Map<String, Object>)
            c.challenge(
                cfg,
                Map.of(),
                Map.of("op", 13, "d", Map.of("plain_token", raw, "event_ts", timestamp)));
    Map<String, String> headers =
        Map.of(
            "x-signature-timestamp",
            timestamp,
            "x-signature-ed25519",
            String.valueOf(signed.get("signature")),
            "x-agent-start-raw-body",
            Base64.getEncoder()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    InboundMessage m = c.parse(cfg, headers, payload);
    assertEquals(ConversationType.GROUP, m.conversationType());
    assertEquals("g1", m.replyTargetId());
    assertEquals("u1", m.senderId());
  }

  @Test
  void qqValidationReturnsEd25519Signature() {
    QQBotConnector c = new QQBotConnector(json);
    Object result =
        c.challenge(
            new QQBotConnector.Config("a", "secret", null, "acc"),
            Map.of(),
            Map.of("op", 13, "d", Map.of("plain_token", "token", "event_ts", "123")));
    assertInstanceOf(Map.class, result);
    assertEquals("token", ((Map<?, ?>) result).get("plain_token"));
    assertEquals(128, String.valueOf(((Map<?, ?>) result).get("signature")).length());
  }

  @Test
  void feishuChallengeDoesNotDispatchMessage() {
    FeishuConnector c = new FeishuConnector(json);
    assertEquals(
        Map.of("challenge", "ok"),
        c.challenge(
            new FeishuConnector.Config("a", "s", "v", null, "acc"),
            Map.of(),
            Map.of("challenge", "ok")));
  }

  @Test
  void feishuDecryptsEncryptedCallback() throws Exception {
    String key = "encrypt-key", plain = "{\"challenge\":\"encrypted-ok\"}";
    byte[] aes =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(
        javax.crypto.Cipher.ENCRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(aes, "AES"),
        new javax.crypto.spec.IvParameterSpec(aes, 0, 16));
    String encrypted =
        Base64.getEncoder()
            .encodeToString(
                cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    FeishuConnector c = new FeishuConnector(json);
    assertEquals(
        Map.of("challenge", "encrypted-ok"),
        c.challenge(
            new FeishuConnector.Config("a", "s", null, key, "acc"),
            Map.of(),
            Map.of("encrypt", encrypted)));
  }

  @Test
  void weComDecryptsAndVerifiesEncryptedCallback() throws Exception {
    String corpId = "corp-id",
        token = "callback-token",
        timestamp = "1730000000",
        nonce = "nonce",
        xml =
            "<xml><FromUserName>user-1</FromUserName><MsgId>message-1</MsgId><Content>hello</Content></xml>";
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    String encodingKey = Base64.getEncoder().withoutPadding().encodeToString(key);
    byte[] message = xml.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        corp = corpId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int unpadded = 20 + message.length + corp.length, pad = 32 - unpadded % 32;
    java.nio.ByteBuffer plain = java.nio.ByteBuffer.allocate(unpadded + pad);
    plain.put(new byte[16]).putInt(message.length).put(message).put(corp);
    for (int i = 0; i < pad; i++) plain.put((byte) pad);
    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
    cipher.init(
        javax.crypto.Cipher.ENCRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(key, "AES"),
        new javax.crypto.spec.IvParameterSpec(key, 0, 16));
    String encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(plain.array()));
    String[] parts = {token, timestamp, nonce, encrypted};
    Arrays.sort(parts);
    String signature =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-1")
                    .digest(
                        String.join("", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    WeComConnector c = new WeComConnector(json);
    InboundMessage inbound =
        c.parse(
            new WeComConnector.Config(corpId, "secret", "1", token, encodingKey, "acc"),
            Map.of(),
            Map.of(
                "Encrypt",
                encrypted,
                "msg_signature",
                signature,
                "timestamp",
                timestamp,
                "nonce",
                nonce));
    assertEquals("message-1", inbound.messageId());
    assertEquals("user-1", inbound.replyTargetId());
    assertEquals("hello", inbound.text());
  }

  @Test
  void weComPlainCallbackStillRequiresConfiguredTokenSignature() throws Exception {
    String token = "callback-token", timestamp = "1730000000", nonce = "nonce";
    String[] parts = {token, timestamp, nonce};
    Arrays.sort(parts);
    String signature =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-1")
                    .digest(
                        String.join("", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    WeComConnector connector = new WeComConnector(json);
    WeComConnector.Config config =
        new WeComConnector.Config("corp", "secret", "1", token, null, "acc");
    Map<String, Object> payload =
        Map.of(
            "FromUserName",
            "user-1",
            "MsgId",
            "message-1",
            "Content",
            "hello",
            "timestamp",
            timestamp,
            "nonce",
            nonce,
            "msg_signature",
            signature);

    assertEquals("message-1", connector.parse(config, Map.of(), payload).messageId());
    Map<String, Object> unsigned = new LinkedHashMap<>(payload);
    unsigned.remove("msg_signature");
    assertThrows(ChannelException.class, () -> connector.parse(config, Map.of(), unsigned));
  }

  @Test
  void webhookRequiresConfiguredToken() {
    WebhookConnector c = new WebhookConnector(json);
    WebhookConnector.Config cfg =
        new WebhookConnector.Config("https://example.test", null, "secret", "acc");
    assertThrows(
        ChannelException.class,
        () -> c.parse(cfg, Map.of(), Map.of("messageId", "1", "senderId", "u", "content", "x")));
    InboundMessage m =
        c.parse(
            cfg,
            Map.of("x-agent-start-token", "secret"),
            Map.of("messageId", "1", "senderId", "u", "content", "x"));
    assertEquals("u", m.replyTargetId());
  }

  @Test
  void platformBusinessErrorsAreNotReportedAsSuccessfulSends() {
    assertDoesNotThrow(() -> PlatformMessages.requireSuccess(Map.of("code", 0), "feishu", "code"));
    ChannelException failure =
        assertThrows(
            ChannelException.class,
            () ->
                PlatformMessages.requireSuccess(
                    Map.of("code", 230001, "msg", "permission denied"), "feishu", "code"));
    assertEquals("feishu_230001", failure.code());
    assertEquals("permission denied", failure.getMessage());
  }

  @Test
  void runtimePublishesAllNativeChannels() {
    NativeChannelRuntimeProvider runtime =
        new NativeChannelRuntimeProvider(
            List.of(
                new QQBotConnector(json),
                new FeishuConnector(json),
                new DingTalkConnector(json),
                new WeComConnector(json),
                new EmailConnector(),
                new WebhookConnector(json)),
            json);
    assertEquals(
        Set.of("qqbot", "feishu", "dingtalk", "wecom", "email", "webhook"),
        runtime.discoverChannels().stream()
            .map(ChannelDefinition::channelId)
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void everyNativeChannelPublishesItsCompleteConfigurationContract() {
    List<NativeChannelConnector<?>> connectors =
        List.of(
            new QQBotConnector(json),
            new FeishuConnector(json),
            new DingTalkConnector(json),
            new WeComConnector(json),
            new EmailConnector(),
            new WebhookConnector(json));
    Map<String, Set<String>> expected =
        Map.of(
            "qqbot", Set.of("appId", "clientSecret", "apiBase", "transport", "intents"),
            "feishu",
                Set.of("appId", "appSecret", "verificationToken", "encryptKey", "transport"),
            "dingtalk", Set.of("clientId", "clientSecret", "webhookUrl", "callbackToken"),
            "wecom", Set.of("corpId", "corpSecret", "agentId", "token", "encodingAesKey"),
            "email",
                Set.of(
                    "smtpHost",
                    "smtpPort",
                    "imapHost",
                    "imapPort",
                    "username",
                    "password",
                    "from",
                    "ssl",
                    "startTls",
                    "pollIntervalSeconds"),
            "webhook", Set.of("outboundUrl", "callbackToken", "bearerToken"));
    for (NativeChannelConnector<?> connector : connectors) {
      Set<String> actual = new HashSet<>(properties(connector.credentialSchema()));
      actual.addAll(properties(connector.configurationSchema()));
      assertEquals(expected.get(connector.id()), actual, connector.id());
    }
  }

  @Test
  void runtimeRejectsMissingRequiredFieldsBeforeCallingAPlatform() {
    NativeChannelRuntimeProvider runtime =
        new NativeChannelRuntimeProvider(List.of(new QQBotConnector(json)), json);
    ChannelException failure =
        assertThrows(
            ChannelException.class,
            () ->
                runtime.saveAccount(
                    new io.github.aigoodle.connector.channel.SaveChannelAccountRequest(
                        "qqbot", "account", "QQ", false, Map.of())));
    assertEquals("invalid_configuration", failure.code());
    assertTrue(failure.getMessage().contains("appId"));
    assertTrue(failure.getMessage().contains("clientSecret"));
  }

  @Test
  void webhookAccountsAreReadyButNotReportedAsPersistentConnections() {
    CapturingConnector connector = new CapturingConnector();
    NativeChannelRuntimeProvider runtime =
        new NativeChannelRuntimeProvider(List.of(connector), json);

    io.github.aigoodle.connector.channel.ChannelAccount account =
        runtime.saveAccount(
            new io.github.aigoodle.connector.channel.SaveChannelAccountRequest(
                "capture", "account", "Capture", true, Map.of()));

    assertTrue(account.running());
    assertFalse(account.connected());
    assertEquals("webhook", account.metadata().get("transport"));
  }

  @Test
  void runtimePreservesOriginalConversationAndReplyMessage() {
    CapturingConnector connector = new CapturingConnector();
    NativeChannelRuntimeProvider runtime =
        new NativeChannelRuntimeProvider(List.of(connector), json);
    runtime.saveAccount(
        new io.github.aigoodle.connector.channel.SaveChannelAccountRequest(
            "capture", "account", "Capture", true, Map.of()));
    runtime.sendWithResult(
        new io.github.aigoodle.connector.channel.ChannelOutboundMessage(
            "capture",
            "account",
            "group-1",
            "group-1",
            "reply",
            "TEXT",
            List.of(),
            Map.of("conversationType", "GROUP"),
            Map.of("replyToPlatformMessageId", "message-1", "idempotencyKey", "key-1")));
    assertEquals("message-1", connector.sent.replyToMessageId());
    assertEquals("GROUP", connector.sent.metadata().get("conversationType"));
  }

  private static final class CapturingConnector
      implements NativeChannelConnector<Map<String, Object>> {
    OutboundMessage sent;

    public String id() {
      return "capture";
    }

    @SuppressWarnings("unchecked")
    public Class<Map<String, Object>> configType() {
      return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    public ChannelDescriptor descriptor() {
      return new ChannelDescriptor(
          id(), "Capture", "test", "1", ChannelCapabilities.text(), Map.of());
    }

    public Map<String, Object> credentialSchema() {
      return Map.of();
    }

    public ConnectionTestResult test(Map<String, Object> c) {
      return ConnectionTestResult.ok();
    }

    public SendResult send(Map<String, Object> c, OutboundMessage m) {
      sent = m;
      return SendResult.accepted("sent");
    }
  }

  @SuppressWarnings("unchecked")
  private static Set<String> properties(Map<String, Object> schema) {
    Object value = schema.get("properties");
    return value instanceof Map<?, ?> map ? ((Map<String, Object>) map).keySet() : Set.of();
  }
}
