package io.github.aigoodle.channel.api;

public record InboundReceipt(boolean accepted, String code) {
  public static InboundReceipt acknowledge() {
    return new InboundReceipt(true, "accepted");
  }
}
