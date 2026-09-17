package io.github.aigoodle.connectors.nativebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aigoodle.connector.channel.*;
import io.github.aigoodle.connector.connection.ConnectorSecretCodec;
import io.github.aigoodle.connector.persistence.ChannelConnectionMapper;
import io.github.aigoodle.connectors.core.ChannelConnectorRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationListener;
import org.springframework.web.bind.annotation.RestController;

@AutoConfiguration(
    afterName = {
      "io.github.aigoodle.connector.config.GoodleConnectorAutoConfiguration",
      "io.github.aigoodle.connectors.core.ChannelConnectorAutoConfiguration"
    })
public class NativeConnectorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  NativeAccountStore nativeAccountStore(
      ChannelConnectionMapper mapper, ConnectorSecretCodec secrets) {
    return new JdbcNativeAccountStore(mapper, secrets);
  }

  @Bean
  @ConditionalOnMissingBean
  NativeInboundBridge nativeInboundBridge(
      ObjectProvider<ChannelInboundDispatcher> d, ObjectProvider<ChannelEventLogService> e) {
    return new NativeInboundBridge(d, e);
  }

  @Bean
  @ConditionalOnMissingBean
  NativeChannelRuntimeProvider nativeChannelRuntimeProvider(
      ChannelConnectorRegistry connectorRegistry,
      ObjectMapper j,
      ObjectProvider<NativeAccountStore> store,
      NativeInboundBridge sink) {
    java.util.List<NativeChannelConnector<?>> nativeConnectors = new java.util.ArrayList<>();
    for (var connector : connectorRegistry.all()) {
      if (connector instanceof NativeChannelConnector<?> nativeConnector) {
        nativeConnectors.add(nativeConnector);
      }
    }
    return new NativeChannelRuntimeProvider(
        nativeConnectors, j, store.getIfAvailable(), sink);
  }

  @Bean
  ApplicationListener<ApplicationReadyEvent> nativeAccountSessionStarter(
      NativeChannelRuntimeProvider runtime) {
    return event -> runtime.restoreEnabledAccounts();
  }

  @Bean
  @ConditionalOnClass(RestController.class)
  @ConditionalOnBean({ChannelInboundDispatcher.class, ChannelEventLogService.class})
  NativeChannelCallbackController nativeChannelCallbackController(
      NativeChannelRuntimeProvider r,
      ChannelInboundDispatcher d,
      ChannelEventLogService e,
      ObjectMapper j) {
    return new NativeChannelCallbackController(r, d, e, j);
  }
}
