package io.github.aigoodle.connectors.feishu;

import io.github.aigoodle.connectors.nativebot.NativeConnectorAutoConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the Feishu channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeConnectorAutoConfiguration.class)
public class FeishuConnectorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(FeishuConnector.class)
  FeishuConnector feishuConnector(ObjectMapper j) {
    return new FeishuConnector(j);
  }
}
