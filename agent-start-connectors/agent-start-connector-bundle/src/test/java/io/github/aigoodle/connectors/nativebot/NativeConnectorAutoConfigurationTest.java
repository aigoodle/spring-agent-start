package io.github.aigoodle.connectors.nativebot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aigoodle.connectors.dingtalk.DingTalkConnectorAutoConfiguration;
import io.github.aigoodle.connectors.email.EmailConnectorAutoConfiguration;
import io.github.aigoodle.connectors.feishu.FeishuConnectorAutoConfiguration;
import io.github.aigoodle.connectors.qqbot.QQBotConnectorAutoConfiguration;
import io.github.aigoodle.connectors.webhook.WebhookConnectorAutoConfiguration;
import io.github.aigoodle.connectors.wecom.WeComConnectorAutoConfiguration;
import io.github.aigoodle.connector.channel.ChannelEventLogService;
import io.github.aigoodle.connector.channel.ChannelInboundDispatcher;
import io.github.aigoodle.connector.connection.ConnectorSecretCodec;
import io.github.aigoodle.connector.persistence.ChannelConnectionMapper;
import io.github.aigoodle.connectors.core.ChannelConnectorAutoConfiguration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NativeConnectorAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ChannelConnectorAutoConfiguration.class,
                  NativeConnectorAutoConfiguration.class,
                  QQBotConnectorAutoConfiguration.class,
                  WeComConnectorAutoConfiguration.class,
                  FeishuConnectorAutoConfiguration.class,
                  DingTalkConnectorAutoConfiguration.class,
                  EmailConnectorAutoConfiguration.class,
                  WebhookConnectorAutoConfiguration.class))
          .withUserConfiguration(HostBeans.class);

  @Test
  void registersAllAdaptersRuntimePersistenceBridgeAndCallbackController() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(NativeChannelRuntimeProvider.class);
          assertThat(context).hasSingleBean(NativeAccountStore.class);
          assertThat(context).hasSingleBean(NativeInboundBridge.class);
          assertThat(context).hasSingleBean(NativeChannelCallbackController.class);

          Set<String> ids =
              context.getBeansOfType(NativeChannelConnector.class).values().stream()
                  .map(NativeChannelConnector::id)
                  .collect(Collectors.toSet());
          assertThat(ids)
              .containsExactlyInAnyOrder(
                  "qqbot", "feishu", "dingtalk", "wecom", "email", "webhook");
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class HostBeans {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    ChannelConnectionMapper channelConnectionMapper() {
      return mock(ChannelConnectionMapper.class);
    }

    @Bean
    ConnectorSecretCodec connectorSecretCodec() {
      return mock(ConnectorSecretCodec.class);
    }

    @Bean
    ChannelInboundDispatcher channelInboundDispatcher() {
      return mock(ChannelInboundDispatcher.class);
    }

    @Bean
    ChannelEventLogService channelEventLogService() {
      return mock(ChannelEventLogService.class);
    }
  }
}
