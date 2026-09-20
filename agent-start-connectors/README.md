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

Every new module should also package exactly one frontend manifest at
`src/main/resources/META-INF/agent-start/channels/<channel-id>.yml`. The runtime scans this location
across every installed jar. The YAML is authoritative for form schemas, labels, account ownership,
instance policy and identity binding; Java declarations remain a compatibility fallback for older
third-party modules. Manifests are parsed with SnakeYAML safe mode and rejected on duplicate keys,
duplicate channel IDs, unsupported versions, invalid account enums, or required fields not present
in `properties`.

```yaml
schemaVersion: 1
id: acme
name: Acme
description: Acme enterprise messaging
version: "1"
accountModel:
  scope: TENANT             # PERSONAL | TENANT
  instancePolicy: MULTIPLE  # SINGLE | MULTIPLE
  ownerRequired: false
  identityBridge:
    enabled: true
    mode: OAUTH
    externalIdentityLabel: Acme 用户
    enterpriseIdentityLabel: 企业员工
credentialSchema:
  type: object
  required: [token]
  properties:
    token:
      type: string
      title: API Token
      description: Acme 管理后台生成的访问密钥
      writeOnly: true
configurationSchema:
  type: object
  properties:
    region:
      type: string
      title: 区域
      enum: [cn, global]
      default: cn
uiSchema:
  fields:
    token: { widget: password, placeholder: "输入 API Token" }
metadata:
  platformId: acme
  icon: Acme
```

The catalog API serializes these JSON Schemas and publishes the account contract under
`metadata.accountModel`; shared frontends must render the contract instead of branching on the
channel ID. OAuth, QR-code and device authorization remain explicit UI/runtime extensions.

```java
@Component
final class AcmeConnector implements NativeChannelConnector<AcmeConnector.Config> {
  record Config(String token, String accountId) {}

  public String id() { return "acme"; }
  public Class<Config> configType() { return Config.class; }
  public ChannelDescriptor descriptor() {
    return new ChannelDescriptor(id(), "Acme", "Acme bot", "1",
        ChannelCapabilities.text(), ChannelAccountModel.personal(), Map.of());
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

Channel accounts explicitly declare an account model. `PERSONAL` credentials require an employee
owner. `TENANT` credentials belong to the tenant itself, do not ask the administrator for a personal
ID, and may declare an identity bridge that maps platform senders to verified employees at runtime.
`SINGLE` or `MULTIPLE` controls the number of instances allowed per tenant. The runtime publishes
this model under `ChannelDefinition.metadata.accountModel`, together with the credential and
configuration JSON Schemas, so clients can render account forms without channel-ID conditionals.

Accounts never contain an application, Agent or workflow binding. A published application subscribes
to `provider + channelId`; the reply still uses the original account, conversation and platform
message ID.

## Built-in transports

| Channel | Inbound | Outbound |
| --- | --- | --- |
| QQBot | Official Gateway WebSocket (signed webhook optional) | OpenAPI v2 C2C/group messages |
| Feishu | Official SDK long connection (event webhook optional) | `im/v1/messages` |
| DingTalk | Official Java Stream SDK | Group and one-to-one robot APIs |
| WeCom | Official AI Bot WebSocket long connection | AI Bot proactive messages |
| Email | IMAP unread-message polling | SMTP |
| Webhook | Token-authenticated JSON callback | Configurable HTTP JSON endpoint |

HTTP callback channels use:

```text
POST /agent-start/channel-events/native/{channelId}/{runtimeAccountId}
```

The runtime account ID is shown on the saved connection. QQ callback verification (`op=13`) is
handled by the same endpoint. WeCom AI Bot accounts connect directly with Bot ID and Secret and do
not need a callback URL. Inbound messages are claimed durably before
dispatch. A workflow end result is enqueued into the Outbox with the original conversation type,
reply target and platform message ID, so delivery is asynchronous and retryable.

## Trusted employee identity

Bot credentials identify the channel account, not the employee sending a message. Every inbound
channel user must therefore resolve to a verified host employee before an Agent or workflow is
executed. Publish a `ChannelIdentityBindingProvider` Spring bean from the host business application.
The request contains tenant, provider, channel, account and the platform-native user ID. The host may
look up an existing SSO/channel binding or start a verified mobile-number flow and return a binding
URL. A mobile number must be verified inside the host application; matching an unverified number or
display name is not sufficient.

```java
@Bean
ChannelIdentityBindingProvider enterpriseChannelIdentities(EmployeeBindings bindings) {
  return new ChannelIdentityBindingProvider() {
    public boolean supports(String provider, String channel) {
      return provider.equals("native") && Set.of("wecom", "dingtalk").contains(channel);
    }

    public Resolution resolve(Request request) {
      return bindings.findVerifiedEmployee(request.tenantId(), request.channelId(),
              request.externalUserId())
          .map(Resolution::verified)
          .orElseGet(() -> Resolution.unbound(
              "请先绑定员工身份", bindings.createOneTimeBindingUrl(request)));
    }
  };
}
```

Verified results are persisted in `agent_channel_identity`. An unbound or rejected sender receives
the binding guidance and is stopped before Agent/workflow execution; the channel account owner is
never used as a substitute identity.

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
