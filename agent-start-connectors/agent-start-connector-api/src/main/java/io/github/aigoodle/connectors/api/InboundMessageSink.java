package io.github.aigoodle.connectors.api;

@FunctionalInterface
public interface InboundMessageSink {
  InboundReceipt accept(InboundMessage message);
}
