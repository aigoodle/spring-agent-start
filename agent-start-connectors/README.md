# Agent Start Connectors

Public, lightweight bot-channel extension surface. Platform connectors translate only between their
protocol and normalized messages. Persistence, tenancy, workflow routing and retries stay outside the SPI.

```text
connector-api <- connector-core <- connector-runtime
                                      ^
                                      +-- connector-qqbot
                                      +-- connector-wecom
                                      +-- connector-feishu
                                      +-- connector-dingtalk
                                      +-- connector-email
                                      +-- connector-webhook

connector-bundle = optional convenience dependency containing all six adapters
```

A third-party connector implements `NativeChannelConnector<C>`, publishes it as a Spring bean, and
verifies the common behavior with `agent-start-connector-testkit`. The platform adapter must not
depend on Agent, Workflow, MyBatis or the web layer. `NativeChannelRuntimeProvider` is the only bridge
to the existing durable channel runtime.

## Adding a channel

The configuration is a plain immutable record. Implement outbound `send`; implement `parse` for an
HTTP callback or `connect` for a long-lived transport. Returning a `ChannelSession` lets the runtime
stop and replace a stream cleanly when an account is edited or disabled.

```java
@Component
final class AcmeConnector implements NativeChannelConnector<AcmeConnector.Config> {
  record Config(String token, String accountId) {}

  public String id() { return "acme"; }
  public Class<Config> configType() { return Config.class; }
  public ChannelDescriptor descriptor() {
    return new ChannelDescriptor(id(), "Acme", "Acme bot", "1",
        ChannelCapabilities.text(), Map.of());
  }
  public Map<String, Object> credentialSchema() {
    return Map.of("type", "object", "required", List.of("token"), "properties",
        Map.of("token", Map.of("type", "string", "title", "API Token", "writeOnly", true)));
  }
  public ConnectionTestResult test(Config config) { /* call the platform */ }
  public SendResult send(Config config, OutboundMessage message) { /* translate and send */ }
  public InboundMessage parse(Config config, Map<String, String> headers,
                              Map<String, Object> payload) { /* verify and normalize */ }
}
```

Connector IDs must be unique. Account IDs, tenant isolation, encrypted credential persistence,
idempotency, workflow dispatch and Outbox retries are supplied by the host and should not be
reimplemented by adapters.

Channel accounts are employee-owned credentials. They never contain an application, Agent or
workflow binding. A published application subscribes to `provider + channelId`; therefore one shared
workflow can receive messages from every active employee account on that channel. At dispatch time
the host resolves the signed platform sender to a verified enterprise employee identity, falling back
to the account owner when no explicit mapping exists, and runs the workflow under that trusted user
context. The reply still uses the original account, conversation and platform message ID.

## Built-in transports

| Channel | Inbound | Outbound |
| --- | --- | --- |
| QQBot | Official Gateway WebSocket (signed webhook optional) | OpenAPI v2 C2C/group messages |
| Feishu | Official SDK long connection (event webhook optional) | `im/v1/messages` |
| DingTalk | Official Java Stream SDK | Group and one-to-one robot APIs |
| WeCom | Signed/encrypted XML callback | Application message API |
| Email | IMAP unread-message polling | SMTP |
| Webhook | Token-authenticated JSON callback | Configurable HTTP JSON endpoint |

HTTP callback channels use:

```text
POST /agent-start/channel-events/native/{channelId}/{runtimeAccountId}
```

The runtime account ID is shown on the saved connection. QQ callback verification (`op=13`) and
WeCom GET verification are handled by the same endpoint. Inbound messages are claimed durably before
dispatch. A workflow end result is enqueued into the Outbox with the original conversation type,
reply target and platform message ID, so delivery is asynchronous and retryable.

## Maven dependencies

Applications should normally import only the channels they use. Every platform artifact brings in
the platform-neutral runtime transitively and publishes its connector through Spring Boot auto-configuration.

```xml
<dependency>
  <groupId>io.github.aigoodle</groupId>
  <artifactId>agent-start-connector-qqbot</artifactId>
</dependency>
```

The standalone server and demo import `agent-start-connector-bundle` so all built-in channels remain
available in the connector catalog. Library users do not need the bundle.

## Live black-box sends

`NativeConnectorLiveTest` is intentionally opt-in. Configure the environment variables for the
platform being tested and run:

```bash
mvn -pl agent-start-connectors/agent-start-connector-bundle -am -Dtest=NativeConnectorLiveTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The variable prefixes are `QQBOT_`, `FEISHU_`, `DINGTALK_`, `WECOM_`, `EMAIL_`, and `WEBHOOK_`;
see the test source for the exact names. A missing credential skips only that platform instead of
reporting a false success.
