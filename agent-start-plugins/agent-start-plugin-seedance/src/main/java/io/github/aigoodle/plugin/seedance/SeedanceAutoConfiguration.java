package io.github.aigoodle.plugin.seedance;

import io.github.aigoodle.plugin.seedance.mapper.SeedanceSubmissionMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.net.URI;
import java.io.IOException;

@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@MapperScan("io.github.aigoodle.plugin.seedance.mapper")
public class SeedanceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public SeedanceSubmissionStore seedanceSubmissionStore(DataSource source, SeedanceSubmissionMapper mapper,
                                                           PlatformTransactionManager transactionManager,
                                                           @Value("${spring-agent.plugins.seedance.initialize-schema:true}") boolean initialize) {
        if (initialize)
            new ResourceDatabasePopulator(new ClassPathResource("db/plugin-seedance-schema.sql")).execute(source);
        return new SeedanceSubmissionStore(mapper, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public SeedanceVideoPlugin seedanceVideoPlugin(SeedanceSubmissionStore store,
                                                   @Value("${spring-agent.plugins.seedance.base-url:https://ark.cn-beijing.volces.com/api/v3}") String baseUrl) throws IOException {
        return new SeedanceVideoPlugin(store, URI.create(baseUrl));
    }
}
