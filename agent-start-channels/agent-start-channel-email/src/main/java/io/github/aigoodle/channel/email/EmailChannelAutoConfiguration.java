package io.github.aigoodle.channel.email;

import io.github.aigoodle.channel.nativebot.NativeChannelAutoConfiguration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Installs only the Email channel adapter when this platform module is present. */
@AutoConfiguration(before = NativeChannelAutoConfiguration.class)
public class EmailChannelAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(EmailChannel.class)
  EmailChannel emailChannel() {
    return new EmailChannel();
  }
}
