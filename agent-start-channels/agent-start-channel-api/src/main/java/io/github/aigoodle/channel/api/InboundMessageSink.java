package io.github.aigoodle.channel.api;

@FunctionalInterface
public interface InboundMessageSink {
  InboundReceipt accept(InboundMessage message);
}
