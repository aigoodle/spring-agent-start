package io.github.aigoodle.connector.redis;

import io.github.aigoodle.connector.channel.ChannelOutboundRateLimiter;
import io.github.aigoodle.connector.config.GoodleConnectorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

/** Activates shared connector quotas when the embedding host supplies Spring Data Redis. */
@AutoConfiguration(after = DataRedisAutoConfiguration.class, before = GoodleConnectorAutoConfiguration.class)
@ConditionalOnBean(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "spring-agent.connector.channel-rate-limit", name = "backend",
        havingValue = "redis", matchIfMissing = true)
public class GoodleConnectorRedisAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ChannelOutboundRateLimiter.class)
    public ChannelOutboundRateLimiter redisChannelOutboundRateLimiter(
            StringRedisTemplate redis,
            @Value("${spring-agent.connector.channel-rate-limit.key-prefix:agent-start:channel-rate}")
            String keyPrefix) {
        return new RedisChannelOutboundRateLimiter(redis, keyPrefix);
    }
}
