package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.channel.ChannelEventLogService;
import io.github.aigoodle.connector.channel.ChannelInboundDispatcher;
import io.github.aigoodle.connector.channel.ChannelInboundEvent;
import io.github.aigoodle.connector.channel.ChannelInboundResult;
import io.github.aigoodle.connector.hermes.HermesProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated data-plane callback used by the Hermes Agent Start platform plugin. */
@RestController
@ConditionalOnBean({ChannelInboundDispatcher.class, HermesProperties.class})
@RequestMapping("/channel-events/hermes")
public class HermesChannelEventController {
    private final ChannelInboundDispatcher dispatcher;
    private final ChannelEventLogService events;
    private final HermesProperties properties;

    public HermesChannelEventController(ChannelInboundDispatcher dispatcher, ChannelEventLogService events,
                                        HermesProperties properties) {
        this.dispatcher = dispatcher; this.events = events; this.properties = properties;
    }

    @PostMapping public ChannelInboundResult receive(
            @RequestHeader(value="X-Agent-Start-Token", required=false) String token,
            @RequestBody ChannelInboundEvent event) {
        validate(token, event);
        var claim = events.claimInbound(event, java.util.UUID.randomUUID().toString());
        if (claim.priorResult() != null) return acknowledgement(claim.priorResult());
        if (claim.busy()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "message is already being processed; retry later");
        long started = System.nanoTime();
        try (ChannelEventLogService.InboundLease ignored = events.keepAlive(claim)) {
            ChannelInboundResult result;
            try { result = dispatcher.dispatch(event); }
            catch (RuntimeException ex) {
                events.completeInbound(claim, event, null, (System.nanoTime() - started) / 1_000_000, ex);
                throw ex;
            }
            events.completeInbound(claim, event, result, (System.nanoTime() - started) / 1_000_000, null);
            return acknowledgement(result);
        }
    }

    private void validate(String token, ChannelInboundEvent event) {
        String expected = properties.getBridgeToken();
        if (expected == null || expected.isBlank() || !expected.equals(token))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid Hermes bridge token");
        if (event == null || !"hermes".equals(event.provider()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider must be hermes");
        if (event.messageId() == null || event.messageId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId is required for reliable delivery");
    }

    private static ChannelInboundResult acknowledgement(ChannelInboundResult result) {
        if (result == null) return ChannelInboundResult.unhandled();
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(result.metadata());
        if (result.reply() != null && !result.reply().isBlank()) metadata.put("replyQueued", true);
        return new ChannelInboundResult(result.handled(), null, result.code(), metadata);
    }
}
