package io.github.aigoodle.plugin.example;

import io.github.aigoodle.connector.ConnectorKey;
import io.github.aigoodle.connector.connection.ConnectorConnectionService.ResolvedConnection;
import io.github.aigoodle.connector.execution.*;
import io.github.aigoodle.plugin.config.PluginAutoConfiguration;
import io.github.aigoodle.plugin.host.PluginHostCapability;
import io.github.aigoodle.plugin.runtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ProductVideoStarterTest {
    @Test void addingStarterDiscoversActionAndPackagedSkill() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PluginAutoConfiguration.class, ProductVideoAutoConfiguration.class))
                .withPropertyValues("spring-agent.plugin.grants[example.product-video-java][0]=product.read")
                .withBean(PluginConnectionResolver.class, () -> request -> new ResolvedConnection(Map.of(), Map.of()))
                .withBean(PluginHostCapability.class, () -> new PluginHostCapability() {
                    public String name() { return "product.read"; }
                    public Object execute(ConnectorExecutionContext identity, Map<String, Object> args) { return Map.of("name", "Product " + args.get("productId")); }
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    var provider = context.getBean(PluginConnectorProvider.class);
                    assertThat(provider.manifests().getFirst().skills()).hasSize(1);
                    var result = provider.execute(new ConnectorExecutionRequest(new ConnectorKey("plugin", "example.product-video-java"),
                            "prepare", null, null, Map.of("productId", "123"), ConnectorExecutionContext.anonymous()));
                    assertThat(result.success()).isTrue();
                    assertThat(result.data().toString()).contains("Product 123");
                });
    }
}
