package io.github.aigoodle.connectors.api;

/** Minimal platform extension: translate inbound and outbound messages only. */
public interface ChannelConnector<C> {
  String id();

  Class<C> configType();

  ChannelDescriptor descriptor();

  ConnectionTestResult test(C configuration);

  SendResult send(C configuration, OutboundMessage message);

  default ChannelSession connect(C configuration, InboundMessageSink sink) {
    return ChannelSession.noop();
  }
}
