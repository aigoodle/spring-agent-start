package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.channel.ChannelInboundDispatcher;
import io.github.aigoodle.connector.channel.ChannelInboundEvent;
import io.github.aigoodle.connector.channel.ChannelInboundResult;
import io.github.aigoodle.connector.channel.ChannelEventLogService;
import io.github.aigoodle.connector.openclaw.OpenClawProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/** Authenticated runtime callback. Returns a reply when a bound Spring Agent claims the message. */
@RestController
@ConditionalOnBean(type = {
        "io.github.aigoodle.connector.channel.ChannelInboundDispatcher",
        "io.github.aigoodle.connector.openclaw.OpenClawProperties"
})
@RequestMapping("/channel-events/openclaw")
public class ChannelEventController {
    public record DeliveryReceipt(String provider, String channelId, String accountId,
                                  String platformMessageId, LocalDateTime deliveredAt) {}
    private final ChannelInboundDispatcher dispatcher;
    private final OpenClawProperties properties;
    private final ChannelEventLogService eventLog;

    public ChannelEventController(ChannelInboundDispatcher dispatcher, OpenClawProperties properties,
                                  ChannelEventLogService eventLog) {
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.eventLog = eventLog;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public ChannelInboundResult receive(
            @RequestHeader(value = "X-Agent-Start-Token", required = false) String token,
            @RequestBody ChannelInboundEvent event) {
        validate(token, event);
        ChannelEventLogService.InboundClaim claim = eventLog.claimInbound(event, java.util.UUID.randomUUID().toString());
        if (claim.priorResult() != null) return acknowledgement(claim.priorResult());
        if (claim.busy()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "message is already being processed; retry later");
        long started = System.nanoTime();
        try (ChannelEventLogService.InboundLease ignored = eventLog.keepAlive(claim)) {
            ChannelInboundResult result;
            try {
                result = dispatcher.dispatch(event);
            } catch (RuntimeException ex) {
                eventLog.completeInbound(claim, event, null, (System.nanoTime() - started) / 1_000_000, ex);
                throw ex;
            }
            eventLog.completeInbound(claim, event, result, (System.nanoTime() - started) / 1_000_000, null);
            return acknowledgement(result);
        }
    }

    @PostMapping("/observe")
    @ResponseStatus(HttpStatus.OK)
    public ChannelInboundResult observe(
            @RequestHeader(value = "X-Agent-Start-Token", required = false) String token,
            @RequestBody ChannelInboundEvent event) {
        validate(token, event);
        ChannelInboundResult result = ChannelInboundResult.unhandled();
        try {
            eventLog.record(event, result, 0, null);
        } catch (RuntimeException ignored) {
            // Duplicate observation or temporary storage failure must not affect the channel.
        }
        return result;
    }

    @PostMapping("/delivery")
    @ResponseStatus(HttpStatus.OK)
    public java.util.Map<String, Boolean> delivery(
            @RequestHeader(value = "X-Agent-Start-Token", required = false) String token,
            @RequestBody DeliveryReceipt receipt) {
        validateToken(token);
        if (receipt == null || !"openclaw".equals(receipt.provider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider must be openclaw");
        }
        boolean updated = eventLog.markDelivered(receipt.provider(), receipt.channelId(), receipt.accountId(),
                receipt.platformMessageId(), receipt.deliveredAt());
        return java.util.Map.of("updated", updated);
    }

    private void validate(String token, ChannelInboundEvent event) {
        validateToken(token);
        if (event == null || !"openclaw".equals(event.provider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider must be openclaw");
        }
        if (event.messageId() == null || event.messageId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId is required for reliable delivery");
        }
    }

    private void validateToken(String token) {
        String expected = properties.getServiceToken();
        if (expected == null || expected.isBlank() || !expected.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid runtime callback token");
        }
    }

    private static ChannelInboundResult acknowledgement(ChannelInboundResult result) {
        if (result == null) return ChannelInboundResult.unhandled();
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(result.metadata());
        if (result.reply() != null && !result.reply().isBlank()) metadata.put("replyQueued", true);
        return new ChannelInboundResult(result.handled(), null, result.code(), metadata);
    }
}
