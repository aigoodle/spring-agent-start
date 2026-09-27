package io.github.aigoodle.channel.nativebot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aigoodle.channel.dingtalk.DingTalkConnectorAutoConfiguration;
import io.github.aigoodle.channel.email.EmailConnectorAutoConfiguration;
import io.github.aigoodle.channel.feishu.FeishuConnectorAutoConfiguration;
import io.github.aigoodle.channel.qqbot.QQBotConnectorAutoConfiguration;
import io.github.aigoodle.channel.webhook.WebhookConnectorAutoConfiguration;
import io.github.aigoodle.channel.wecom.WeComConnectorAutoConfiguration;
import io.github.aigoodle.channel.ChannelEventLogService;
import io.github.aigoodle.channel.ChannelInboundDispatcher;
import io.github.aigoodle.common.crypto.TenantSecretCodec;
import io.github.aigoodle.channel.persistence.ChannelConnectionMapper;
import io.github.aigoodle.channel.core.ChannelConnectorAutoConfiguration;
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
          assertThat(context).hasSingleBean(ObjectMapper.class);
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
    ChannelConnectionMapper channelConnectionMapper() {
      return mock(ChannelConnectionMapper.class);
    }

    @Bean
    TenantSecretCodec connectorSecretCodec() {
      return mock(TenantSecretCodec.class);
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
