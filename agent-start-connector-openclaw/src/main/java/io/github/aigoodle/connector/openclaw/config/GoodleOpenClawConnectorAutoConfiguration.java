package io.github.aigoodle.connector.openclaw.config;

import io.github.aigoodle.connector.openclaw.*;
import io.github.aigoodle.connector.provider.ConnectorProvider;
import io.github.aigoodle.connector.channel.ChannelRuntimeProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureBefore(io.github.aigoodle.connector.config.GoodleConnectorAutoConfiguration.class)
@EnableConfigurationProperties(OpenClawProperties.class)
@ConditionalOnProperty(prefix = "spring-agent.connector.openclaw", name = "enabled", havingValue = "true")
public class GoodleOpenClawConnectorAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public OpenClawGatewayClient openClawGatewayClient(OpenClawProperties properties) {
        return new RestOpenClawGatewayClient(properties);
    }
    @Bean @ConditionalOnMissingBean(name = "openClawConnectorProvider")
    public ConnectorProvider openClawConnectorProvider(OpenClawGatewayClient client) {
        return new OpenClawConnectorProvider(client);
    }
    @Bean @ConditionalOnMissingBean(name = "openClawChannelRuntimeProvider")
    public ChannelRuntimeProvider openClawChannelRuntimeProvider(OpenClawGatewayClient client) {
        return new OpenClawChannelRuntimeProvider(client);
    }
}
