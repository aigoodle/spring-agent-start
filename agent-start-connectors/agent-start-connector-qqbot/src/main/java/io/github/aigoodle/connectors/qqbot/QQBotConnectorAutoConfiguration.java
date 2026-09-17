package io.github.aigoodle.connectors.qqbot;

import io.github.aigoodle.connectors.nativebot.NativeConnectorAutoConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the QQBot channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeConnectorAutoConfiguration.class)
public class QQBotConnectorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(QQBotConnector.class)
  QQBotConnector qqbotConnector(ObjectMapper j) {
    return new QQBotConnector(j);
  }
}
