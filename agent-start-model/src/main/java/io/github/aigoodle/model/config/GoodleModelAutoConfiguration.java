package io.github.aigoodle.model.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import io.github.aigoodle.common.crypto.AesGcmTextEncryptor;
import io.github.aigoodle.common.crypto.TextEncryptor;
import io.github.aigoodle.model.mapper.ModelMapper;
import io.github.aigoodle.model.mapper.PredefinedModelMapper;
import io.github.aigoodle.model.mapper.PromptTemplateMapper;
import io.github.aigoodle.model.mapper.ProviderCredentialMapper;
import io.github.aigoodle.model.mapper.ProviderDefinitionMapper;
import io.github.aigoodle.model.mapper.ProviderModelSettingMapper;
import io.github.aigoodle.model.mapper.TenantDefaultModelMapper;
import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.model.provider.builtin.BuiltinModelProviders;
import io.github.aigoodle.model.provider.builtin.OllamaModelProvider;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import io.github.aigoodle.model.runtime.ModelInstanceFactory;
import io.github.aigoodle.model.service.CredentialCodec;
import io.github.aigoodle.model.service.DerivedTenantCredentialEncryptor;
import io.github.aigoodle.model.service.TenantCredentialEncryptor;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.ModelConnectionTester;
import io.github.aigoodle.model.service.PromptTemplateService;
import io.github.aigoodle.model.service.ProviderCredentialService;
import io.github.aigoodle.model.service.ProviderCatalogClient;
import io.github.aigoodle.model.service.ProviderDefinitionSeeder;
import io.github.aigoodle.model.service.ProviderDefinitionService;
import io.github.aigoodle.model.service.ProviderModelSettingsService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration for the model module. Importing the {@code agent-start-model}
 * jar makes provider registry, credential encryption, the instance factory and the
 * services available with zero manual wiring.
 * <p>
 * Built-in providers are added to the registry directly (not as individual beans) and
 * any {@link ModelProvider} the application publishes as a Spring bean — e.g. a
 * third-party connector jar — is merged in as well.
 */
@AutoConfiguration
@EnableConfigurationProperties(GoodleModelProperties.class)
@MapperScan("io.github.aigoodle.model.mapper")
public class GoodleModelAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public io.github.aigoodle.model.video.VideoModelService videoModelService(ModelService models, ModelProviderRegistry providers,
            ProviderDefinitionService definitions, ProviderModelSettingsService settings) {
        return new io.github.aigoodle.model.video.VideoModelService(models, providers, definitions, settings);
    }

    @Bean
    @ConditionalOnMissingBean
    public TextEncryptor agentTextEncryptor(GoodleModelProperties properties) {
        return new AesGcmTextEncryptor(properties.getEncryptionSecret());
    }

    @Bean
    @ConditionalOnMissingBean
    public CredentialCodec credentialCodec(TextEncryptor encryptor,
            ObjectProvider<TenantCredentialEncryptor> tenantEncryptors,
            GoodleModelProperties properties) {
        TenantCredentialEncryptor tenantEncryptor = tenantEncryptors.getIfAvailable(
                () -> new DerivedTenantCredentialEncryptor(properties.getEncryptionSecret()));
        return new CredentialCodec(encryptor, tenantEncryptor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "agentAuditMetaObjectHandler")
    public MetaObjectHandler agentAuditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelProviderRegistry modelProviderRegistry(ObjectProvider<ModelProvider> providerBeans,
                                                       GoodleModelProperties properties) {
        List<ModelProvider> all = new ArrayList<>();
        // Application / third-party providers published as beans take precedence.
        providerBeans.orderedStream().forEach(all::add);
        if (properties.isRegisterBuiltinProviders()) {
            all.add(new OllamaModelProvider());
            all.addAll(BuiltinModelProviders.openAiCompatible());
        }
        return new ModelProviderRegistry(all);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelInstanceFactory modelInstanceFactory(ModelProviderRegistry registry,
            org.springframework.beans.factory.ObjectProvider<io.github.aigoodle.model.runtime.ChatModelDecorator> decorators) {
        return new ModelInstanceFactory(registry, decorators.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderCredentialService providerCredentialService(ProviderCredentialMapper mapper,
                                                               CredentialCodec codec) {
        return new ProviderCredentialService(mapper, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderCatalogClient providerCatalogClient(ModelProviderRegistry registry,
                                                       ProviderCredentialService credentialService,
                                                       CredentialCodec codec) {
        return new ProviderCatalogClient(registry, credentialService, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelConnectionTester modelConnectionTester(ModelProviderRegistry registry,
                                                       ModelInstanceFactory instanceFactory) {
        return new ModelConnectionTester(registry, instanceFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelService modelService(ModelMapper modelMapper,
                                     ProviderCredentialService credentialService,
                                     CredentialCodec codec,
                                     ModelProviderRegistry registry,
                                       ModelInstanceFactory instanceFactory,
                                       ProviderDefinitionService definitionService,
                                       ProviderModelSettingsService settingsService,
                                       ModelConnectionTester connectionTester,
                                       ProviderCatalogClient providerCatalogClient) {
        return new ModelService(modelMapper, credentialService, codec, registry, instanceFactory,
                definitionService, settingsService, connectionTester, providerCatalogClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptTemplateService promptTemplateService(PromptTemplateMapper mapper) {
        return new PromptTemplateService(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderDefinitionService providerDefinitionService(ProviderDefinitionMapper providerMapper,
                                                               PredefinedModelMapper predefinedMapper) {
        return new ProviderDefinitionService(providerMapper, predefinedMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderModelSettingsService providerModelSettingsService(ProviderModelSettingMapper settingMapper,
                                                                     TenantDefaultModelMapper defaultMapper) {
        return new ProviderModelSettingsService(settingMapper, defaultMapper);
    }

    /**
     * Insert built-in provider definitions and predefined models only when the
     * corresponding database rows are missing. Registered as an application-ready
     * listener, but established catalogs remain read-only during normal restarts.
     */
    @Bean
    @ConditionalOnMissingBean
    public ProviderDefinitionSeeder providerDefinitionSeeder(ModelProviderRegistry registry,
                                                             ProviderDefinitionService service) {
        return new ProviderDefinitionSeeder(registry, service);
    }

}
