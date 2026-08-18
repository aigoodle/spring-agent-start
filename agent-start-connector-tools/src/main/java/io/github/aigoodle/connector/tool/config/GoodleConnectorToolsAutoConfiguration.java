package io.github.aigoodle.connector.tool.config;

import io.github.aigoodle.connector.execution.ConnectorExecutionGateway;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import io.github.aigoodle.connector.tool.ConnectorToolProvider;
import io.github.aigoodle.tool.ToolProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class GoodleConnectorToolsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "connectorToolProvider")
    public ToolProvider connectorToolProvider(ConnectorRegistry registry,
                                              ConnectorExecutionGateway gateway) {
        return new ConnectorToolProvider(registry, gateway);
    }
}
