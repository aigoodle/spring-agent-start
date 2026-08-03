package io.github.aigoodle.tool.config;

import io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration;
import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.ToolProvider;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.tool.adapter.AgentToolCallback;
import io.github.aigoodle.tool.builtin.CalculatorTool;
import io.github.aigoodle.tool.builtin.CurrentTimeTool;
import io.github.aigoodle.tool.builtin.HttpGetTool;
import io.github.aigoodle.tool.mcp.McpClientManager;
import io.github.aigoodle.tool.mcp.McpProperties;
import io.github.aigoodle.tool.mcp.McpToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the tools module: registers built-in tools, the
 * {@link ToolRegistry}, and — via a post-processor — one {@link ToolCallback} bean per
 * {@link AgentTool} so any agent ({@code ChatClient} / workflow AGENT node) that
 * collects {@code List<ToolCallback>} sees every tool automatically. Built-ins can be
 * turned off with {@code spring-agent.tools.builtin=false}.
 */
@AutoConfiguration(after = SpringAgentModelAutoConfiguration.class)
public class SpringAgentToolsAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "spring-agent.tools", name = "builtin", havingValue = "true", matchIfMissing = true)
    public CalculatorTool calculatorTool() {
        return new CalculatorTool();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring-agent.tools", name = "builtin", havingValue = "true", matchIfMissing = true)
    public CurrentTimeTool currentTimeTool() {
        return new CurrentTimeTool();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring-agent.tools", name = "builtin", havingValue = "true", matchIfMissing = true)
    public HttpGetTool httpGetTool() {
        return new HttpGetTool();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ObjectProvider<AgentTool> declaredTools,
                                     ObjectProvider<ToolProvider> toolProviders) {
        return new ToolRegistry(declaredTools.orderedStream().toList(),
                toolProviders.orderedStream().toList());
    }

    /**
     * MCP client integration — exposes the tools of configured MCP servers as
     * {@link ToolProvider} tools. Active only when the MCP SDK is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.modelcontextprotocol.client.McpSyncClient")
    @EnableConfigurationProperties(McpProperties.class)
    static class McpConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        public McpClientManager mcpClientManager(McpProperties properties) {
            return new McpClientManager(properties.getServers());
        }

        @Bean
        @ConditionalOnMissingBean(name = "mcpToolProvider")
        public ToolProvider mcpToolProvider(McpClientManager clientManager) {
            return new McpToolProvider(clientManager);
        }
    }

    /**
     * For every {@link AgentTool} bean, register a wrapping {@link ToolCallback} bean so
     * it is discoverable by {@code List<ToolCallback>} injection (the agent layer).
     */
    @Bean
    public static AgentToolCallbackRegistrar agentToolCallbackRegistrar() {
        return new AgentToolCallbackRegistrar();
    }

    static class AgentToolCallbackRegistrar implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            // resolution needs a bean factory; handled in postProcessBeanFactory
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
                return;
            }
            for (String toolBeanName : beanFactory.getBeanNamesForType(AgentTool.class, true, false)) {
                String callbackBeanName = toolBeanName + "ToolCallback";
                if (registry.containsBeanDefinition(callbackBeanName)) {
                    continue;
                }
                ConstructorArgumentValues constructorArguments = new ConstructorArgumentValues();
                constructorArguments.addIndexedArgumentValue(0, new RuntimeBeanReference(toolBeanName));
                RootBeanDefinition callbackDefinition = new RootBeanDefinition(AgentToolCallback.class);
                callbackDefinition.setConstructorArgumentValues(constructorArguments);
                registry.registerBeanDefinition(callbackBeanName, callbackDefinition);
            }
        }
    }
}
