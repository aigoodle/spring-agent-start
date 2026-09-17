package io.github.aigoodle.connectors.testkit;

import static org.junit.jupiter.api.Assertions.*;

import io.github.aigoodle.connectors.api.ChannelConnector;
import org.junit.jupiter.api.Test;

public interface ChannelConnectorContract<C> {
  ChannelConnector<C> connector();

  C validConfiguration();

  @Test
  default void hasStableMatchingIdentity() {
    assertFalse(connector().id().isBlank());
    assertEquals(connector().id(), connector().descriptor().id());
  }

  @Test
  default void declaresConfigurationAndCapabilities() {
    assertNotNull(connector().configType());
    assertNotNull(connector().descriptor().capabilities());
  }

  @Test
  default void validConfigurationCanBeTested() {
    assertNotNull(connector().test(validConfiguration()));
  }
}
