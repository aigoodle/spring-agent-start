package io.github.aigoodle.connectors.email;

import io.github.aigoodle.connectors.nativebot.NativeConnectorAutoConfiguration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the Email channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeConnectorAutoConfiguration.class)
public class EmailConnectorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(EmailConnector.class)
  EmailConnector emailConnector() {
    return new EmailConnector();
  }
}
