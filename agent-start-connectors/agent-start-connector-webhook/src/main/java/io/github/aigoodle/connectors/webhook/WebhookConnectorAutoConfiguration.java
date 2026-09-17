package io.github.aigoodle.connectors.webhook;

import io.github.aigoodle.connectors.nativebot.NativeConnectorAutoConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the Webhook channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeConnectorAutoConfiguration.class)
public class WebhookConnectorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(WebhookConnector.class)
  WebhookConnector webhookConnector(ObjectMapper j) {
    return new WebhookConnector(j);
  }
}
