package io.github.aigoodle.channel.webhook;

import io.github.aigoodle.channel.nativebot.NativeChannelAutoConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the Webhook channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeChannelAutoConfiguration.class)
public class WebhookChannelAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(WebhookChannel.class)
  WebhookChannel webhookChannel(ObjectMapper j) {
    return new WebhookChannel(j);
  }
}
