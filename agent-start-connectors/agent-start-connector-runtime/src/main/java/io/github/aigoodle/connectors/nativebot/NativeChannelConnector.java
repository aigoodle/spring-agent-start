package io.github.aigoodle.connectors.nativebot;

import io.github.aigoodle.connectors.api.ChannelConnector;
import io.github.aigoodle.connectors.api.InboundMessage;
import java.util.Map;

/** Optional webhook boundary implemented by native bot connectors. */
public interface NativeChannelConnector<C> extends ChannelConnector<C> {
  Map<String, Object> credentialSchema();

  default Map<String, Object> configurationSchema() {
    return Map.of("type", "object", "properties", Map.of());
  }

  default InboundMessage parse(
      C configuration, Map<String, String> headers, Map<String, Object> payload) {
    throw new UnsupportedOperationException(id() + " does not receive HTTP callbacks");
  }

  default Object challenge(
      C configuration, Map<String, String> headers, Map<String, Object> payload) {
    return null;
  }
}
