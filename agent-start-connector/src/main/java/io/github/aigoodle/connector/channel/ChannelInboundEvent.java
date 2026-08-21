package io.github.aigoodle.connector.channel;

import java.time.Instant;
import java.util.Map;
import java.util.List;

/** Normalized inbound message emitted by any channel runtime. */
public record ChannelInboundEvent(
        String provider,
        String channelId,
        String accountId,
        String messageId,
        String senderId,
        String conversationId,
        String content,
        String messageType,
        List<ChannelAttachment> attachments,
        Map<String, Object> contentPayload,
        Instant timestamp,
        boolean group,
        Map<String, Object> metadata,
        String runtimeNodeId) {

    public ChannelInboundEvent {
        messageType = messageType == null || messageType.isBlank() ? "TEXT" : messageType.trim().toUpperCase();
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        contentPayload = contentPayload == null ? Map.of() : Map.copyOf(contentPayload);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        runtimeNodeId = runtimeNodeId == null || runtimeNodeId.isBlank() ? null : runtimeNodeId.trim();
    }

    /** Backward-compatible constructor for providers that have not propagated a runtime node yet. */
    public ChannelInboundEvent(String provider, String channelId, String accountId, String messageId,
                               String senderId, String conversationId, String content, String messageType,
                               List<ChannelAttachment> attachments, Map<String, Object> contentPayload,
                               Instant timestamp, boolean group, Map<String, Object> metadata) {
        this(provider, channelId, accountId, messageId, senderId, conversationId, content, messageType,
                attachments, contentPayload, timestamp, group, metadata, null);
    }

    public ChannelInboundEvent(String provider, String channelId, String accountId, String messageId,
                               String senderId, String conversationId, String content, Instant timestamp,
                               boolean group, Map<String, Object> metadata) {
        this(provider, channelId, accountId, messageId, senderId, conversationId, content, "TEXT",
                List.of(), Map.of(), timestamp, group, metadata, null);
    }
}
