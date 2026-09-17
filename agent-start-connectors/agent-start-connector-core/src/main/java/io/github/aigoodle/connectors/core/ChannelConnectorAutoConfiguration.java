package io.github.aigoodle.connectors.core;

import io.github.aigoodle.connectors.api.ChannelConnector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ChannelConnectorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  ChannelConnectorRegistry channelConnectorRegistry(
      ObjectProvider<ChannelConnector<?>> connectors) {
    return new ChannelConnectorRegistry(connectors.orderedStream().toList());
  }
}
