package io.github.aigoodle.plugin.agent;

import io.github.aigoodle.connector.execution.ConnectorExecutionGateway;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import io.github.aigoodle.tool.execution.ToolExecutionContextProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PluginAgentAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public PluginSkillTools pluginSkillTools(ObjectProvider<io.github.aigoodle.plugin.runtime.PluginConnectorProvider> plugins,
            ObjectProvider<io.github.aigoodle.connector.installation.ConnectorInstallationService> installations) {
        return new PluginSkillTools(plugins::getObject, installations::getObject);
    }
    @Bean @ConditionalOnMissingBean(PluginModelCapability.class)
    public PluginModelCapability pluginModelCapability(ObjectProvider<io.github.aigoodle.model.service.ModelService> models) {
        return new PluginModelCapability(models::getObject);
    }
    @Bean @ConditionalOnMissingBean
    public PluginAgentRuntime pluginAgentRuntime(ObjectProvider<ConnectorExecutionGateway> gateway,
            ObjectProvider<ConnectorRegistry> registry, ObjectProvider<ToolExecutionContextProvider> identity) {
        return new PluginAgentRuntime(gateway::getObject, registry::getObject,
                () -> identity.getObject().currentContext());
    }
}
