package io.github.aigoodle.knowledge.elasticsearch;

import io.github.aigoodle.knowledge.config.SpringAgentKnowledgeAutoConfiguration;
import io.github.aigoodle.knowledge.index.VectorStoreFactory;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers an Elasticsearch-backed {@link VectorStoreFactory} when
 * {@code spring-agent.knowledge.vector-store=elasticsearch}. Constructs a low-level
 * {@link RestClient} from properties unless the app provides its own bean.
 */
@AutoConfiguration(before = SpringAgentKnowledgeAutoConfiguration.class)
@ConditionalOnClass({ElasticsearchVectorStore.class, RestClient.class})
@ConditionalOnProperty(prefix = "spring-agent.knowledge", name = "vector-store", havingValue = "elasticsearch")
@EnableConfigurationProperties(ElasticsearchStoreProperties.class)
public class SpringAgentElasticsearchStoreAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RestClient agentElasticsearchRestClient(ElasticsearchStoreProperties properties) {
        HttpHost[] hosts = properties.getUris().stream()
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
        RestClientBuilder builder = RestClient.builder(hosts);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.setDefaultHeaders(new org.apache.http.Header[]{
                    new org.apache.http.message.BasicHeader("Authorization", "ApiKey " + properties.getApiKey())
            });
        } else if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword()));
            builder.setHttpClientConfigCallback(hc -> hc.setDefaultCredentialsProvider(credentials));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean(VectorStoreFactory.class)
    public VectorStoreFactory elasticsearchVectorStoreFactory(RestClient restClient,
                                                              ElasticsearchStoreProperties properties) {
        return new ElasticsearchVectorStoreFactory(restClient, properties);
    }
}
