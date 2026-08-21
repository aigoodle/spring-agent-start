package io.github.aigoodle.connector.hermes.config;

import io.github.aigoodle.connector.channel.ChannelRuntimeProvider;
import io.github.aigoodle.connector.hermes.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureBefore(io.github.aigoodle.connector.config.GoodleConnectorAutoConfiguration.class)
@EnableConfigurationProperties(HermesProperties.class)
@ConditionalOnProperty(prefix="spring-agent.connector.hermes", name="enabled", havingValue="true")
public class GoodleHermesConnectorAutoConfiguration {
    @Bean @ConditionalOnMissingBean public HermesDashboardClient hermesDashboardClient(HermesProperties p) { return new RestHermesDashboardClient(p); }
    @Bean @ConditionalOnMissingBean public HermesBridgeClient hermesBridgeClient(HermesProperties p) { return new RestHermesBridgeClient(p); }
    @Bean @ConditionalOnMissingBean(name="hermesChannelRuntimeProvider") public ChannelRuntimeProvider hermesChannelRuntimeProvider(HermesDashboardClient c, HermesBridgeClient b) { return new HermesChannelRuntimeProvider(c, b); }
}
