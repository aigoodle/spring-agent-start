package io.github.aigoodle.persistence;

import org.apache.ibatis.plugin.Interceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(Interceptor.class)
@EnableConfigurationProperties(TenantPersistenceProperties.class)
public class TenantPersistenceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(TenantSqlGuardInterceptor.class)
    @ConditionalOnProperty(prefix = "spring-agent.persistence.tenant-guard", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public TenantSqlGuardInterceptor tenantSqlGuardInterceptor(TenantPersistenceProperties properties) {
        return new TenantSqlGuardInterceptor(properties.protectedTables());
    }
}
