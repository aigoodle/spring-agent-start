package io.github.aigoodle.connectors.wecom;

import io.github.aigoodle.connectors.nativebot.NativeConnectorAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the WeCom channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeConnectorAutoConfiguration.class)
public class WeComConnectorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(WeComConnector.class)
  WeComConnector wecomConnector(ObjectMapper j) {
    return new WeComConnector(j);
  }
}
