package io.github.aigoodle.connectors.dingtalk;

import io.github.aigoodle.connectors.nativebot.NativeConnectorAutoConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the DingTalk channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeConnectorAutoConfiguration.class)
public class DingTalkConnectorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(DingTalkConnector.class)
  DingTalkConnector dingtalkConnector(ObjectMapper j) {
    return new DingTalkConnector(j);
  }
}
