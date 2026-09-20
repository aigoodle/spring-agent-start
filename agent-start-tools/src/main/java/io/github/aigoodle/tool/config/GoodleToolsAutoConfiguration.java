package io.github.aigoodle.tool.config;

import io.github.aigoodle.model.config.GoodleModelAutoConfiguration;
import io.github.aigoodle.tool.ToolDefinition;
import io.github.aigoodle.tool.ToolProvider;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.tool.adapter.ToolDefinitionCallback;
import io.github.aigoodle.tool.annotation.AnnotatedToolProvider;
import io.github.aigoodle.tool.builtin.CalculatorTool;
import io.github.aigoodle.tool.builtin.CurrentTimeTool;
import io.github.aigoodle.tool.builtin.HttpGetTool;
import io.github.aigoodle.tool.mcp.McpClientManager;
import io.github.aigoodle.tool.mcp.McpProperties;
import io.github.aigoodle.tool.mcp.McpToolProvider;
import io.github.aigoodle.tool.execution.DefaultToolExecutionGateway;
import io.github.aigoodle.tool.execution.CurrentUserToolExecutionContextProvider;
import io.github.aigoodle.tool.execution.ToolExecutionContextProvider;
import io.github.aigoodle.tool.execution.ToolExecutionGateway;
import io.github.aigoodle.tool.execution.ToolExecutionListener;
import io.github.aigoodle.tool.execution.ToolExecutionPolicy;
import io.github.aigoodle.tool.execution.ToolExecutionProperties;
import io.github.aigoodle.tool.custom.CustomToolManager;
import io.github.aigoodle.tool.custom.CustomToolProperties;
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
 * {@link ToolDefinition} so any agent ({@code ChatClient} / workflow AGENT node) that
 * collects {@code List<ToolCallback>} sees every tool automatically. Built-ins can be
 * turned off with {@code spring-agent.tools.builtin=false}.
 */
@AutoConfiguration(after = GoodleModelAutoConfiguration.class)
@EnableConfigurationProperties({ToolExecutionProperties.class, CustomToolProperties.class})
public class GoodleToolsAutoConfiguration {

    @Bean
    public static AnnotatedToolProvider annotatedToolProvider(ObjectProvider<ToolRegistry> registryProvider) {
        return new AnnotatedToolProvider(registryProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public CustomToolManager customToolManager(CustomToolProperties properties) {
        return new CustomToolManager(properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ToolExecutionGateway toolExecutionGateway(
            ToolExecutionProperties properties,
            ObjectProvider<ToolExecutionPolicy> policies,
            ObjectProvider<ToolExecutionListener> listeners) {
        return new DefaultToolExecutionGateway(properties,
                policies.orderedStream().toList(), listeners.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionContextProvider toolExecutionContextProvider() {
        return new CurrentUserToolExecutionContextProvider();
    }

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
    public ToolRegistry toolRegistry(ObjectProvider<ToolDefinition> declaredTools,
                                     ObjectProvider<ToolProvider> toolProviders,
                                     ToolExecutionGateway executionGateway,
                                     ToolExecutionContextProvider contextProvider) {
        return new ToolRegistry(declaredTools.orderedStream().toList(),
                toolProviders.orderedStream().toList(), executionGateway, contextProvider);
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
            return new McpClientManager(properties.getServers(), properties.getConfigFile(),
                    properties.getEncryptionSecret());
        }

        @Bean
        @ConditionalOnMissingBean(name = "mcpToolProvider")
        public McpToolProvider mcpToolProvider(McpClientManager clientManager) {
            return new McpToolProvider(clientManager);
        }
    }

    /**
     * For every {@link ToolDefinition} bean, register a wrapping {@link ToolCallback} bean so
     * it is discoverable by {@code List<ToolCallback>} injection (the agent layer).
     */
    @Bean
    public static ToolDefinitionCallbackRegistrar agentToolCallbackRegistrar() {
        return new ToolDefinitionCallbackRegistrar();
    }

    static class ToolDefinitionCallbackRegistrar implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            // resolution needs a bean factory; handled in postProcessBeanFactory
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
                return;
            }
            String[] gatewayNames = beanFactory.getBeanNamesForType(ToolExecutionGateway.class, true, false);
            String[] contextProviderNames = beanFactory.getBeanNamesForType(
                    ToolExecutionContextProvider.class, true, false);
            if (gatewayNames.length == 0 || contextProviderNames.length == 0) return;
            for (String toolBeanName : beanFactory.getBeanNamesForType(ToolDefinition.class, true, false)) {
                String callbackBeanName = toolBeanName + "ToolCallback";
                if (registry.containsBeanDefinition(callbackBeanName)) {
                    continue;
                }
                ConstructorArgumentValues constructorArguments = new ConstructorArgumentValues();
                constructorArguments.addIndexedArgumentValue(0, new RuntimeBeanReference(toolBeanName));
                constructorArguments.addIndexedArgumentValue(1, new RuntimeBeanReference(gatewayNames[0]));
                constructorArguments.addIndexedArgumentValue(2, new RuntimeBeanReference(contextProviderNames[0]));
                RootBeanDefinition callbackDefinition = new RootBeanDefinition(ToolDefinitionCallback.class);
                callbackDefinition.setConstructorArgumentValues(constructorArguments);
                registry.registerBeanDefinition(callbackBeanName, callbackDefinition);
            }
        }
    }
}
