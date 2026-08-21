# Channel acceptance record — 2026-08-20

This record separates observed production-shaped behavior from tests that still require external
accounts or a cooperating QQ peer. It contains no connector credentials.

## OpenClaw / QQBot outbound and idempotency

- Backend: `agent-start-server` on `127.0.0.1:18090`
- Runtime: healthy `agent-start-openclaw` container
- Connection: one real `openclaw/qqbot` connection, desired `ACTIVE`, runtime `ONLINE`
- Source: an existing persisted inbound QQ event owned by the same connection
- Marker: `AGENT_START_E2E:1787161998-outbound`
- Outbox event: `47690b6400cf45f7d84059049da73465`
- Observed transition: `PENDING -> SENT`
- Delivery attempts: `1`
- Platform message id: present (value intentionally omitted)
- Reply relationship: outbound event retained the source event id
- Idempotency: resubmitting the same idempotency key returned the same Outbox event id and `SENT`
  state; no second event/message was created

This proves the current backend API -> persisted Outbox -> worker -> OpenClaw -> QQ outbound path.
It does not prove inbound echo or delivery/read acknowledgement. The full `QQBotBlackBoxE2ETest`
still requires a QQ peer that echoes its random marker.

### Full-loop attempt at 2026-08-20 04:05 Asia/Shanghai

- Marker: `AGENT_START_E2E:33765ce9-2dc2-40d9-9d73-be0473c8839f`
- Outbox event: `b182cb0acf0f50723b223c23aa1dfe22`
- Outbound result: `SENT`, with a non-empty QQ platform message id
- Wait window: 180 seconds
- Inbound result: no event containing the marker was observed in the same conversation
- OpenClaw log: no inbound occurrence of the marker was recorded during the window

Therefore this attempt proves another real outbound send but is explicitly a failed full-loop
acceptance, not a pass. The evidence is consistent with the QQ peer not echoing the marker; if the peer
did send it, the next investigation point is QQ event subscription/Bridge hook delivery. The test now
prints its marker before sending and reports recent conversation events on timeout so these cases can be
distinguished in future runs.

### Segmented inbound observability added after the failed attempt

The authenticated OpenClaw Bridge `/health` and `/runtime` responses now expose process-scoped,
privacy-safe inbound counters: hook observations, callback attempts, callback successes/failures,
the corresponding latest timestamps, Bridge start time and a bounded latest callback error. Message
content and external-user identifiers are not retained in these health fields. The Java runtime DTO and
Connector Hub status bar expose the same snapshot.

After rebuilding the OpenClaw image, the real runtime returned `UP`; QQBot account
`eb2e22840e4b0bd482d7d2bfdb6e95b3` was configured, running and connected. Its fresh counters were all
zero, which is expected immediately after restart. On the next peer message:

- `observed=0` means OpenClaw's dispatch hook did not see the event;
- `observed>0` with no callback attempt means the sink/channel configuration prevented forwarding;
- a callback failure records the HTTP/network boundary error without persisting message content;
- a callback success proves Bridge-to-Agent-Start delivery, after which database/event routing is the
  next diagnostic boundary.

### Outbound state semantics

- `SENT` means the runtime/channel adapter accepted the send operation and returned successfully. A
  platform message id, when available, is persisted for correlation.
- `DELIVERED` is reserved for an authenticated provider callback that identifies the sent message by
  provider, channel, account and platform message id.
- The currently verified OpenClaw QQBot and Hermes QQBot adapters do not expose terminal delivery or
  read receipts. Their channel definitions therefore advertise `deliveryReceipts=false` and
  `readReceipts=false`; successful sends remain `SENT`.
- OpenClaw's plugin `message_sent`/delivery terminology and Hermes Bridge's callback-queue
  `delivered()` describe adapter or internal callback completion. Neither is evidence that the
  recipient device received or read a QQ message. Agent Start must never synthesize `DELIVERED` from
  those acknowledgements.

## Hermes readiness

- Hermes Dashboard health: HTTP 200 (`0.20.4`)
- Agent Start Hermes Bridge authenticated health: HTTP 200
- Per-profile bridge/router contract suite: 8 tests passed inside the Hermes runtime image
- Java live smoke: Dashboard and authenticated bridge health executed successfully; the outbound
  case was skipped because no profile/target credentials exist
- Bridge container healthcheck: `healthy`
- Controlled process-crash drill: the supervised Python router was killed from inside the container;
  Docker restarted it (`RestartCount=1`), authenticated bridge health recovered, and Agent Start
  remained `UP`
- Bridge profiles: `[]`
- Connected profiles: `[]`
- Persisted Hermes channel connections: none

Hermes infrastructure is reachable, but a real Hermes QQBot E2E result is not yet available because
no Hermes profile/account is configured.

The Hermes file is a Compose overlay, not a standalone project. Validation and startup must merge it
with the base definition:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.hermes.yml config --quiet
docker compose -f docker/docker-compose.yml -f docker/docker-compose.hermes.yml up -d
```

The bridge uses Docker `init: true`, an authenticated healthcheck and `restart: unless-stopped`.
The backend Compose dependency waits for both the Hermes Dashboard and bridge to become healthy.

## Point-in-time resource baseline

| Container | CPU | Memory | PIDs | PID 1 file descriptors |
|---|---:|---:|---:|---:|
| `agent-start-openclaw` | 0.10% | 315.1 MiB | 12 | 34 |
| `agent-start-hermes` | 0.42% | 536.7 MiB | 49 | 9 |
| `agent-start-hermes-bridge` | 0.00% | 27.32 MiB | 1 | not sampled |

These are idle/single-connection snapshots, not capacity claims. The 200-account gate remains open
until 200 real active connections complete the concurrent probe rounds and a 24-hour soak captures
RSS, CPU, file descriptors, reconnects, QQ throttling and disconnect rates.

## Repeatable fleet probe

`ChannelConnectionCapacityAcceptanceTest` probes every persisted `ACTIVE` connection through the real
Provider account-test endpoint. It supports either fixed rounds or a duration-based soak and writes a
machine-readable JSON report even when the latency/success assertions subsequently fail.

Single-account tool baseline observed on 2026-08-20:

- active connections: 1
- online connections / distinct runtime accounts: 1 / 1
- available runtime nodes: `openclaw-default` (the connection uses the provider default rather than
  pinning an explicit node)
- inventory / runtime-node inventory latency: 129 / 2 ms
- latest rounds / probes: 1 / 1
- success rate: 100%
- latest p50 / p95 / max: 1653 / 1653 / 1653 ms
- report: `/tmp/channel-capacity-baseline-v2.json`

The acceptance preflight now rejects insufficient active or online connections, duplicate/missing
runtime-account identities, and a provider with no available runtime node. It writes a failed JSON
report before asserting, so a 200-account run that is under-provisioned still leaves auditable evidence.

This result validates the probe, not fleet capacity. Run the real 200-account 24-hour gate with:

```bash
CHANNEL_CAPACITY_ENABLED=true \
CHANNEL_CAPACITY_EXPECTED_CONNECTIONS=200 \
CHANNEL_CAPACITY_CONCURRENCY=32 \
CHANNEL_CAPACITY_DURATION_SECONDS=86400 \
CHANNEL_CAPACITY_ROUND_INTERVAL_SECONDS=60 \
CHANNEL_CAPACITY_MIN_SUCCESS_RATE=0.99 \
CHANNEL_CAPACITY_MAX_P95_MS=5000 \
CHANNEL_CAPACITY_REPORT=/var/tmp/agent-start/channel-capacity-200.json \
mvn -pl agent-start-web -am -DskipITs \
  -Dtest=ChannelConnectionCapacityAcceptanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

For an authenticated host gateway, set `CHANNEL_CAPACITY_HEADERS_JSON` to the required trusted request
headers. Do not use a browser-supplied tenant header as the authority source.

## Inbound callback burst and idempotency gate

`ChannelInboundBurstAcceptanceTest` complements the real-account fleet probe. It drives the authenticated
runtime callback with unique platform message IDs and then replays every ID. This validates the database
claim, account/tenant/runtime-node ownership, conversation projection and duplicate acknowledgement path;
it deliberately does **not** claim to create QQ WebSocket connections.

The first 200-message run found a production-relevant race: concurrent first messages for one conversation
could lose the conversation insert race inside the transaction that completed the inbound claim. PostgreSQL
then rolled back completion, leaving the event `PROCESSING`. Conversation materialization now happens after
the durable claim in autocommit scope, and the projection can no longer roll back message completion.

Post-fix baseline on 2026-08-20:

- unique inbound messages / duplicate replays: 200 / 200
- concurrency: 32
- initial callback success: 100%
- durable duplicate acknowledgement: 100%
- latency p50 / p95 / max: 309 / 977 / 1058 ms
- persisted events: 200 (`RECEIVED`: 200, `PROCESSING`: 0)
- conversation projection: one conversation, unread count 200
- report: `/tmp/channel-inbound-burst-200-fixed.json`

Conversation projection success/failure is also exported as the low-cardinality Micrometer counter
`spring.agent.channel.conversation.projection` with only `provider`, `channel`, `runtime` and `outcome`
tags. It intentionally excludes tenant, account, conversation and message identifiers. Production
deployments should alert on any `outcome=failure`; startup backfill remains a compatibility safety net,
not a substitute for investigating repeated projection failures.

Repeat with:

```bash
CHANNEL_INBOUND_BURST_ENABLED=true \
CHANNEL_INBOUND_BURST_TOKEN='<runtime callback token>' \
CHANNEL_INBOUND_BURST_ACCOUNT_ID='<real managed runtime account id>' \
CHANNEL_INBOUND_BURST_MESSAGES=200 \
CHANNEL_INBOUND_BURST_CONCURRENCY=32 \
CHANNEL_INBOUND_BURST_MAX_P95_MS=3000 \
CHANNEL_INBOUND_BURST_REPORT=/var/tmp/agent-start/channel-inbound-burst-200.json \
mvn -pl agent-start-web -am \
  -Dtest=ChannelInboundBurstAcceptanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

This closes the 200-message callback burst gate only. The 200-real-account/200-real-QQ-WebSocket and
24-hour soak gates remain open until 200 independent bot credentials are provisioned.
