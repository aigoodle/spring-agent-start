package io.github.aigoodle.connector.hermes;

import io.github.aigoodle.connector.channel.ChannelOutboundMessage;
import io.github.aigoodle.connector.channel.ChannelSendResult;

import java.util.Map;

public interface HermesBridgeClient {
    ChannelSendResult send(ChannelOutboundMessage message);
    /** Authenticated bridge snapshot, including per-profile reachability and connection state. */
    Map<String, Object> status();
    boolean healthy();
}
