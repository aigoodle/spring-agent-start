package io.github.aigoodle.channel.config;

import io.github.aigoodle.channel.ChannelAgentBindingService;
import io.github.aigoodle.channel.ChannelAgentRouter;
import io.github.aigoodle.channel.ChannelAuditService;
import io.github.aigoodle.channel.ChannelCatalogService;
import io.github.aigoodle.channel.ChannelConnectionHealthWorker;
import io.github.aigoodle.channel.ChannelConnectionReconcileWorker;
import io.github.aigoodle.channel.ChannelConnectionService;
import io.github.aigoodle.channel.ChannelConversationBackfill;
import io.github.aigoodle.channel.ChannelConversationService;
import io.github.aigoodle.channel.ChannelEventLogService;
import io.github.aigoodle.channel.ChannelIdentityAuthenticator;
import io.github.aigoodle.channel.ChannelIdentityBindingProvider;
import io.github.aigoodle.channel.ChannelIdentityService;
import io.github.aigoodle.channel.ChannelInboundDispatcher;
import io.github.aigoodle.channel.ChannelInboundHandler;
import io.github.aigoodle.channel.ChannelMessageObserver;
import io.github.aigoodle.channel.ChannelOperationalSnapshotProvider;
import io.github.aigoodle.channel.ChannelOutboundRateLimiter;
import io.github.aigoodle.channel.ChannelOutboxWorker;
import io.github.aigoodle.channel.ChannelRuntimeProvider;
import io.github.aigoodle.channel.ChannelRuntimeRegistry;
import io.github.aigoodle.channel.ChannelSlaNotifier;
import io.github.aigoodle.channel.ChannelSlaReminderWorker;
import io.github.aigoodle.channel.DatabaseChannelOperationalSnapshotProvider;
import io.github.aigoodle.channel.InMemoryChannelOutboundRateLimiter;
import io.github.aigoodle.channel.SpringEventChannelSlaNotifier;
import io.github.aigoodle.channel.persistence.ChannelAuditMapper;
import io.github.aigoodle.channel.persistence.ChannelConnectionMapper;
import io.github.aigoodle.channel.persistence.ChannelConversationMapper;
import io.github.aigoodle.channel.persistence.ChannelEventMapper;
import io.github.aigoodle.channel.persistence.ChannelIdentityMapper;
import io.github.aigoodle.channel.persistence.EmployeeAgentBindingMapper;
import io.github.aigoodle.channel.persistence.TenantAgentBindingMapper;
import io.github.aigoodle.common.crypto.AesGcmTextEncryptor;
import io.github.aigoodle.common.crypto.DerivedTenantTextEncryptor;
import io.github.aigoodle.common.crypto.TenantSecretCodec;
import io.github.aigoodle.common.crypto.TenantTextEncryptor;
import io.github.aigoodle.common.crypto.TextEncryptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Wires the message-ingestion channel runtime. Ordered after the connector-catalog auto
 * configuration so a shared {@link TenantSecretCodec} bean from the connector domain wins when
 * both modules are embedded; standalone channel hosts get the codec built here.
 */
@AutoConfiguration(afterName = "io.github.aigoodle.connector.config.GoodleConnectorAutoConfiguration")
@EnableConfigurationProperties(ChannelProperties.class)
@MapperScan("io.github.aigoodle.channel.persistence")
public class GoodleChannelAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public TenantSecretCodec channelSecretCodec(ObjectProvider<TextEncryptor> encryptors,
                                                ObjectProvider<TenantTextEncryptor> tenantEncryptors,
                                                ChannelProperties properties) {
        TextEncryptor encryptor = encryptors.getIfAvailable(
                () -> new AesGcmTextEncryptor(properties.getEncryptionSecret()));
        TenantTextEncryptor tenantEncryptor = tenantEncryptors.getIfAvailable(
                () -> new DerivedTenantTextEncryptor(properties.getEncryptionSecret()));
        return new TenantSecretCodec(tenantEncryptor, encryptor);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelRuntimeRegistry channelRuntimeRegistry(ObjectProvider<ChannelRuntimeProvider> providers) {
        return new ChannelRuntimeRegistry(providers.orderedStream().toList());
    }

    @Bean @ConditionalOnMissingBean
    public ChannelCatalogService channelCatalogService(ChannelRuntimeRegistry runtimes, ChannelProperties properties) {
        return new ChannelCatalogService(runtimes, properties.getCatalogCacheTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelConnectionService channelConnectionService(ChannelConnectionMapper mapper,
                                                              TenantSecretCodec codec,
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
    @ConditionalOnProperty(prefix = "spring-agent.channel", name = "backfill-enabled",
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
                                                          ChannelProperties properties,
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
                                                                                   ChannelProperties properties) {
        return new DatabaseChannelOperationalSnapshotProvider(events, connections, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelOutboxWorker channelOutboxWorker(ChannelEventLogService events, ChannelProperties properties) {
        return new ChannelOutboxWorker(events, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelConnectionReconcileWorker channelConnectionReconcileWorker(ChannelConnectionService connections,
                                                                               ChannelProperties properties) {
        return new ChannelConnectionReconcileWorker(connections, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelConnectionHealthWorker channelConnectionHealthWorker(ChannelConnectionService connections,
                                                                         ChannelProperties properties) {
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
                                                              ChannelProperties properties) {
        return new ChannelSlaReminderWorker(conversations, notifier, properties);
    }
}
