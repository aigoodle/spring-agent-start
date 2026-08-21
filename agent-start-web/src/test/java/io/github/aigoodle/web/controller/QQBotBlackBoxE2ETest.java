package io.github.aigoodle.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.common.util.JsonUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in black-box QQBot acceptance test. The configured QQ peer must echo any token beginning with
 * {@code AGENT_START_E2E:}; this verifies HTTP enqueue -> DB Outbox -> OpenClaw -> QQ -> OpenClaw
 * inbound callback -> DB timeline instead of testing only the Bridge client.
 */
class QQBotBlackBoxE2ETest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void reliableReplyTraversesQqAndReturnsAsAnInboundEvent() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(env("QQBOT_E2E_ENABLED", "false")));
        String sourceEventId = required("QQBOT_E2E_SOURCE_EVENT_ID");
        String baseUrl = env("QQBOT_E2E_BACKEND_URL", "http://127.0.0.1:18090/agent-start");
        Duration timeout = Duration.ofSeconds(Long.parseLong(env("QQBOT_E2E_TIMEOUT_SECONDS", "90")));
        String marker = "AGENT_START_E2E:" + UUID.randomUUID();
        String key = "qqbot-e2e-" + UUID.randomUUID();
        System.out.println("QQBOT_E2E_MARKER=" + marker);

        JsonNode queued = json(send(request(baseUrl + "/channel-events/" + sourceEventId + "/reply")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(Map.of(
                        "content", marker, "messageType", "TEXT", "idempotencyKey", key))))
                .header("Content-Type", "application/json").build()));
        JsonNode queuedEvent = queued.path("data");
        String outboundId = queuedEvent.path("id").asText();
        String conversationId = queuedEvent.path("conversationId").asText();
        assertThat(outboundId).isNotBlank();
        assertThat(conversationId).isNotBlank();
        assertThat(queuedEvent.path("provider").asText()).isEqualTo("openclaw");
        assertThat(queuedEvent.path("replyToEventId").asText()).isEqualTo(sourceEventId);

        JsonNode sent = awaitEvent(baseUrl, conversationId, timeout, "outbound SENT/DELIVERED event " + outboundId,
                event -> outboundId.equals(event.path("id").asText())
                && ("SENT".equals(event.path("status").asText())
                || "DELIVERED".equals(event.path("status").asText())));
        assertThat(sent.path("platformMessageId").asText()).isNotBlank();
        assertThat(sent.path("idempotencyKey").asText()).isEqualTo(key);
        assertThat(sent.path("replyToEventId").asText()).isEqualTo(sourceEventId);

        JsonNode echoed = awaitEvent(baseUrl, conversationId, timeout, "inbound QQ echo containing " + marker,
                event -> "INBOUND".equals(event.path("direction").asText())
                && "openclaw".equals(event.path("provider").asText())
                && "qqbot".equals(event.path("channelId").asText())
                && event.path("content").asText().contains(marker));
        assertThat(echoed.path("messageId").asText()).isNotBlank();
        assertThat(echoed.path("conversationId").asText()).isNotBlank();
    }

    private JsonNode awaitEvent(String baseUrl, String conversationId, Duration timeout, String expectation,
                                java.util.function.Predicate<JsonNode> match) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> recent = new ArrayList<>();
        String timelineUrl = baseUrl + "/channel-events/page?limit=200&conversationId="
                + URLEncoder.encode(conversationId, StandardCharsets.UTF_8);
        while (System.nanoTime() < deadline) {
            JsonNode page = json(send(request(timelineUrl).GET().build()));
            Iterator<JsonNode> items = page.path("data").path("items").elements();
            while (items.hasNext()) {
                JsonNode event = items.next();
                if (match.test(event)) return event;
                if (recent.size() < 8) recent.add(summary(event));
            }
            Thread.sleep(500);
        }
        throw new AssertionError("QQBot E2E timed out after " + timeout + " waiting for " + expectation
                + "; recent conversation events=" + recent);
    }

    private static String summary(JsonNode event) {
        String content = event.path("content").asText("");
        if (content.length() > 80) content = content.substring(0, 80) + "…";
        return "{id=" + event.path("id").asText() + ", direction=" + event.path("direction").asText()
                + ", status=" + event.path("status").asText() + ", messageId="
                + event.path("messageId").asText() + ", content=" + content + "}";
    }

    private HttpRequest.Builder request(String uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(10));
        JsonNode headers = JsonUtils.readTree(env("QQBOT_E2E_HEADERS_JSON", "{}"));
        headers.fields().forEachRemaining(entry -> builder.header(entry.getKey(), entry.getValue().asText()));
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return response;
    }

    private static JsonNode json(HttpResponse<String> response) { return JsonUtils.readTree(response.body()); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value.trim();
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value.trim();
    }
}
