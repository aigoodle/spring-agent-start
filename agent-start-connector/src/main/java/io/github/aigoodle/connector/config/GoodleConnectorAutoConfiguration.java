package io.github.aigoodle.connector.config;

import io.github.aigoodle.connector.execution.ConnectorExecutionGateway;
import io.github.aigoodle.connector.execution.ConnectorExecutionPolicy;
import io.github.aigoodle.connector.execution.DefaultConnectorExecutionGateway;
import io.github.aigoodle.connector.provider.ConnectorProvider;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import io.github.aigoodle.connector.connection.ConnectorConnectionService;
import io.github.aigoodle.connector.connection.ConnectorSecretCodec;
import io.github.aigoodle.connector.persistence.ConnectorConnectionMapper;
import io.github.aigoodle.connector.persistence.ConnectorExecutionMapper;
import io.github.aigoodle.connector.persistence.ConnectorInstallationMapper;
import io.github.aigoodle.connector.execution.ConnectorExecutionRecorder;
import io.github.aigoodle.connector.execution.ConnectorExecutionQueryService;
import io.github.aigoodle.connector.installation.ConnectorInstallationService;
import io.github.aigoodle.common.crypto.AesGcmTextEncryptor;
import io.github.aigoodle.common.crypto.TextEncryptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;

@AutoConfiguration
@EnableConfigurationProperties(ConnectorProperties.class)
@MapperScan("io.github.aigoodle.connector.persistence")
public class GoodleConnectorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ConnectorRegistry connectorRegistry(ObjectProvider<ConnectorProvider> providers) {
        return new ConnectorRegistry(providers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectorExecutionGateway connectorExecutionGateway(
            ConnectorRegistry registry, ObjectProvider<ConnectorExecutionPolicy> policies,
            ObjectProvider<ConnectorExecutionRecorder> recorders) {
        return new DefaultConnectorExecutionGateway(registry, policies.orderedStream().toList(),
                recorders.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectorSecretCodec connectorSecretCodec(ObjectProvider<TextEncryptor> encryptors,
                                                      ConnectorProperties properties) {
        TextEncryptor encryptor = encryptors.getIfAvailable(
                () -> new AesGcmTextEncryptor(properties.getEncryptionSecret()));
        return new ConnectorSecretCodec(encryptor);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectorConnectionService connectorConnectionService(
            ConnectorConnectionMapper mapper, ConnectorSecretCodec codec) {
        return new ConnectorConnectionService(mapper, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectorInstallationService connectorInstallationService(
            ConnectorInstallationMapper mapper, ConnectorRegistry registry) {
        return new ConnectorInstallationService(mapper, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectorExecutionRecorder connectorExecutionRecorder(ConnectorExecutionMapper mapper) {
        return new ConnectorExecutionRecorder(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectorExecutionQueryService connectorExecutionQueryService(ConnectorExecutionMapper mapper) {
        return new ConnectorExecutionQueryService(mapper);
    }
}
