package io.github.aigoodle.connector.config;

import io.github.aigoodle.connector.execution.ConnectorExecutionGateway;
import io.github.aigoodle.connector.execution.ConnectorExecutionPolicy;
import io.github.aigoodle.connector.execution.ChannelIdentityExecutionPolicy;
import io.github.aigoodle.connector.execution.DefaultConnectorExecutionGateway;
import io.github.aigoodle.connector.provider.ConnectorProvider;
import io.github.aigoodle.connector.registry.ConnectorRegistry;
import io.github.aigoodle.connector.connection.ConnectorConnectionService;
import io.github.aigoodle.connector.connection.ConnectorSecretCodec;
import io.github.aigoodle.connector.connection.TenantTextEncryptor;
import io.github.aigoodle.connector.connection.DerivedTenantTextEncryptor;
import io.github.aigoodle.connector.persistence.ConnectorConnectionMapper;
import io.github.aigoodle.connector.persistence.ConnectorExecutionMapper;
import io.github.aigoodle.connector.persistence.ConnectorInstallationMapper;
import io.github.aigoodle.connector.execution.ConnectorExecutionRecorder;
import io.github.aigoodle.connector.execution.ConnectorExecutionQueryService;
import io.github.aigoodle.connector.installation.ConnectorInstallationService;
import io.github.aigoodle.connector.channel.ChannelRuntimeProvider;
import io.github.aigoodle.connector.channel.ChannelRuntimeRegistry;
import io.github.aigoodle.connector.channel.ChannelCatalogService;
import io.github.aigoodle.connector.channel.ChannelConnectionService;
import io.github.aigoodle.connector.persistence.ChannelConnectionMapper;
import io.github.aigoodle.connector.channel.ChannelInboundDispatcher;
import io.github.aigoodle.connector.channel.ChannelInboundHandler;
import io.github.aigoodle.connector.channel.ChannelEventLogService;
import io.github.aigoodle.connector.channel.ChannelOutboxWorker;
import io.github.aigoodle.connector.channel.ChannelConnectionReconcileWorker;
import io.github.aigoodle.connector.channel.ChannelConnectionHealthWorker;
import io.github.aigoodle.connector.channel.ChannelOutboundRateLimiter;
import io.github.aigoodle.connector.channel.InMemoryChannelOutboundRateLimiter;
import io.github.aigoodle.connector.channel.ChannelMessageObserver;
import io.github.aigoodle.connector.channel.ChannelOperationalSnapshotProvider;
import io.github.aigoodle.connector.channel.DatabaseChannelOperationalSnapshotProvider;
import io.github.aigoodle.connector.persistence.ChannelEventMapper;
import io.github.aigoodle.connector.channel.ChannelAgentBindingService;
import io.github.aigoodle.connector.channel.ChannelAgentRouter;
import io.github.aigoodle.connector.persistence.TenantAgentBindingMapper;
import io.github.aigoodle.connector.persistence.EmployeeAgentBindingMapper;
import io.github.aigoodle.connector.channel.ChannelIdentityService;
import io.github.aigoodle.connector.channel.ChannelIdentityAuthenticator;
import io.github.aigoodle.connector.channel.ChannelIdentityBindingProvider;
import io.github.aigoodle.connector.persistence.ChannelIdentityMapper;
import io.github.aigoodle.connector.persistence.ChannelAuditMapper;
import io.github.aigoodle.connector.channel.ChannelAuditService;
import io.github.aigoodle.connector.channel.ChannelConversationService;
import io.github.aigoodle.connector.channel.ChannelSlaNotifier;
import io.github.aigoodle.connector.channel.ChannelSlaReminderWorker;
import io.github.aigoodle.connector.channel.SpringEventChannelSlaNotifier;
import org.springframework.context.ApplicationEventPublisher;
import io.github.aigoodle.connector.channel.ChannelConversationBackfill;
import io.github.aigoodle.connector.persistence.ChannelConversationMapper;
import io.github.aigoodle.common.crypto.AesGcmTextEncryptor;
import io.github.aigoodle.common.crypto.TextEncryptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;

@AutoConfiguration
@EnableConfigurationProperties(ConnectorProperties.class)
@MapperScan("io.github.aigoodle.connector.persistence")
public class GoodleConnectorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ChannelIdentityExecutionPolicy.class)
    public ChannelIdentityExecutionPolicy channelIdentityExecutionPolicy() {
        return new ChannelIdentityExecutionPolicy();
    }
    @Bean
    @ConditionalOnMissingBean
    public ChannelRuntimeRegistry channelRuntimeRegistry(ObjectProvider<ChannelRuntimeProvider> providers) {
        return new ChannelRuntimeRegistry(providers.orderedStream().toList());
    }
    @Bean @ConditionalOnMissingBean
    public ChannelCatalogService channelCatalogService(ChannelRuntimeRegistry runtimes, ConnectorProperties properties) {
        return new ChannelCatalogService(runtimes, properties.getChannelCatalogCacheTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelConnectionService channelConnectionService(ChannelConnectionMapper mapper,
                                                              ConnectorSecretCodec codec,
                                                              ChannelRuntimeRegistry runtimes) {
        return new ChannelConnectionService(mapper, codec, runtimes);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelAgentBindingService channelAgentBindingService(TenantAgentBindingMapper tenants,
                                                                  EmployeeAgentBindingMapper employees) {
        return new ChannelAgentBindingService(tenants, employees);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelAgentRouter channelAgentRouter(ChannelConnectionService connections,
                                                  ChannelAgentBindingService bindings) {
        return new ChannelAgentRouter(connections, bindings);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelIdentityService channelIdentityService(ChannelIdentityMapper mapper) {
        return new ChannelIdentityService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelIdentityAuthenticator channelIdentityAuthenticator(
            ChannelIdentityService identities,
            ObjectProvider<ChannelIdentityBindingProvider> providers) {
        return new ChannelIdentityAuthenticator(identities, providers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelAuditService channelAuditService(ChannelAuditMapper mapper) {
        return new ChannelAuditService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelConversationService channelConversationService(ChannelConversationMapper mapper) {
        return new ChannelConversationService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "spring-agent.connector.channel", name = "backfill-enabled",
            havingValue = "true")
    public ChannelConversationBackfill channelConversationBackfill(ChannelEventMapper events,
                                                                   ChannelConversationService conversations) {
        return new ChannelConversationBackfill(events, conversations);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelInboundDispatcher channelInboundDispatcher(ObjectProvider<ChannelInboundHandler> handlers) {
        return new ChannelInboundDispatcher(handlers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelOutboundRateLimiter channelOutboundRateLimiter() {
        return new InMemoryChannelOutboundRateLimiter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelEventLogService channelEventLogService(ChannelEventMapper mapper,
                                                          ChannelConnectionService connections,
                                                          ChannelInboundDispatcher dispatcher,
                                                          ChannelRuntimeRegistry runtimes,
                                                          ConnectorProperties properties,
                                                          ChannelConversationService conversations,
                                                          ChannelOutboundRateLimiter rateLimiter,
                                                          ObjectProvider<ChannelMessageObserver> observer) {
        return new ChannelEventLogService(mapper, connections, dispatcher, runtimes, properties, conversations,
                rateLimiter, observer.getIfAvailable(() -> ChannelMessageObserver.NOOP));
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelOperationalSnapshotProvider channelOperationalSnapshotProvider(ChannelEventMapper events,
                                                                                   ChannelConnectionMapper connections,
                                                                                   ConnectorProperties properties) {
        return new DatabaseChannelOperationalSnapshotProvider(events, connections, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelOutboxWorker channelOutboxWorker(ChannelEventLogService events, ConnectorProperties properties) {
        return new ChannelOutboxWorker(events, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelConnectionReconcileWorker channelConnectionReconcileWorker(ChannelConnectionService connections,
                                                                               ConnectorProperties properties) {
        return new ChannelConnectionReconcileWorker(connections, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelConnectionHealthWorker channelConnectionHealthWorker(ChannelConnectionService connections,
                                                                         ConnectorProperties properties) {
        return new ChannelConnectionHealthWorker(connections, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelSlaNotifier channelSlaNotifier(ApplicationEventPublisher publisher) {
        return new SpringEventChannelSlaNotifier(publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelSlaReminderWorker channelSlaReminderWorker(ChannelConversationService conversations,
                                                              ChannelSlaNotifier notifier,
                                                              ConnectorProperties properties) {
        return new ChannelSlaReminderWorker(conversations, notifier, properties);
    }

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
                                                      ObjectProvider<TenantTextEncryptor> tenantEncryptors,
                                                      ConnectorProperties properties) {
        TextEncryptor encryptor = encryptors.getIfAvailable(
                () -> new AesGcmTextEncryptor(properties.getEncryptionSecret()));
        TenantTextEncryptor tenantEncryptor = tenantEncryptors.getIfAvailable(
                () -> new DerivedTenantTextEncryptor(properties.getEncryptionSecret()));
        return new ConnectorSecretCodec(tenantEncryptor, encryptor);
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
