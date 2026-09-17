package io.github.aigoodle.connectors.core;

import io.github.aigoodle.connectors.api.ChannelConnector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChannelConnectorRegistry {
  private final Map<String, ChannelConnector<?>> connectors;

  public ChannelConnectorRegistry(List<ChannelConnector<?>> discovered) {
    Map<String, ChannelConnector<?>> values = new LinkedHashMap<>();
    for (ChannelConnector<?> connector :
        discovered == null ? List.<ChannelConnector<?>>of() : discovered) {
      String id = normalize(connector.id());
      if (!id.equals(normalize(connector.descriptor().id())))
        throw new IllegalArgumentException("Connector descriptor id does not match: " + id);
      if (connector.configType() == null)
        throw new IllegalArgumentException("Connector configType is required: " + id);
      if (values.putIfAbsent(id, connector) != null)
        throw new IllegalArgumentException("Duplicate connector: " + id);
    }
    connectors = Map.copyOf(values);
  }

  public List<ChannelConnector<?>> all() {
    return List.copyOf(connectors.values());
  }

  public ChannelConnector<?> require(String id) {
    ChannelConnector<?> connector = connectors.get(normalize(id));
    if (connector == null) throw new IllegalArgumentException("Unknown channel connector: " + id);
    return connector;
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Connector id is required");
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
