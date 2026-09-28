package io.github.aigoodle.channel.wecom;

import io.github.aigoodle.channel.nativebot.NativeChannelAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the WeCom channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeChannelAutoConfiguration.class)
public class WeComChannelAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(WeComChannel.class)
  WeComChannel wecomChannel(ObjectMapper j) {
    return new WeComChannel(j);
  }
}
