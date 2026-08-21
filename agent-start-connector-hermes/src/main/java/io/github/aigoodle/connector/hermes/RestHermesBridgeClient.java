package io.github.aigoodle.connector.hermes;

import io.github.aigoodle.connector.channel.ChannelOutboundMessage;
import io.github.aigoodle.connector.channel.ChannelSendResult;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated data-plane client exposed by the Agent Start Hermes plugin. */
public final class RestHermesBridgeClient implements HermesBridgeClient {
    private final RestClient client;
    private final String token;

    public RestHermesBridgeClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeout()); factory.setReadTimeout(properties.getTimeout());
        this.client = RestClient.builder().baseUrl(properties.getBridgeBaseUrl()).requestFactory(factory).build();
        this.token = properties.getBridgeToken();
    }

    @Override public ChannelSendResult send(ChannelOutboundMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", message.accountId()); body.put("platform", message.channelId());
        body.put("chatId", message.targetId()); body.put("conversationId", message.conversationId());
        body.put("content", message.content()); body.put("messageType", message.messageType());
        body.put("attachments", message.attachments()); body.put("contentPayload", message.contentPayload());
        body.put("metadata", message.metadata());
        Map<String, Object> response = client.post().uri("/v1/messages")
                .header("X-Agent-Start-Token", requiredToken()).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(new ParameterizedTypeReference<>() {});
        if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
            throw new IllegalStateException(response == null ? "Hermes bridge returned no response"
                    : String.valueOf(response.getOrDefault("error", "Hermes bridge send failed")));
        }
        Object id = response.get("messageId");
        return new ChannelSendResult(id == null ? null : String.valueOf(id), response);
    }

    @Override public Map<String, Object> status() {
        Map<String, Object> response = client.get().uri("/health")
                .header("X-Agent-Start-Token", requiredToken()).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return response == null ? Map.of() : response;
    }

    @Override public boolean healthy() {
        try { return Boolean.TRUE.equals(status().get("ok")); }
        catch (RuntimeException unavailable) { return false; }
    }

    private String requiredToken() {
        if (token == null || token.isBlank()) throw new IllegalStateException("Hermes bridge token is not configured");
        return token;
    }
}
