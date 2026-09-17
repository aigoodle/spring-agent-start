package io.github.aigoodle.connectors.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.aigoodle.connectors.api.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelConnectorRegistryTest {
  @Test
  void registersAndResolvesCaseInsensitively() {
    ChannelConnectorRegistry registry =
        new ChannelConnectorRegistry(List.of(connector("qqbot", "qqbot")));
    assertEquals("qqbot", registry.require("QQBOT").id());
  }

  @Test
  void rejectsDuplicateIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChannelConnectorRegistry(
                List.of(connector("qqbot", "qqbot"), connector("QQBOT", "QQBOT"))));
  }

  @Test
  void rejectsDescriptorMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChannelConnectorRegistry(List.of(connector("qqbot", "other"))));
  }

  private ChannelConnector<Map> connector(String id, String descriptorId) {
    return new ChannelConnector<>() {
      public String id() {
        return id;
      }

      public Class<Map> configType() {
        return Map.class;
      }

      public ChannelDescriptor descriptor() {
        return new ChannelDescriptor(
            descriptorId, id, "", "1", ChannelCapabilities.text(), Map.of());
      }

      public ConnectionTestResult test(Map value) {
        return ConnectionTestResult.ok();
      }

      public SendResult send(Map value, OutboundMessage message) {
        return SendResult.accepted("1");
      }
    };
  }
}
