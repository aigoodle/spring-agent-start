package io.github.aigoodle.channel.redis;

import io.github.aigoodle.channel.ChannelOutboundRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GoodleConnectorRedisAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GoodleChannelRedisAutoConfiguration.class));

    @Test
    void activatesByDefaultWhenEmbeddingHostProvidesRedis() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class)).run(context -> {
            assertThat(context).hasSingleBean(ChannelOutboundRateLimiter.class);
            assertThat(context.getBean(ChannelOutboundRateLimiter.class))
                    .isInstanceOf(RedisChannelOutboundRateLimiter.class);
        });
    }

    @Test
    void embeddingHostCanForceTheMemoryBackend() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withPropertyValues("spring-agent.channel.rate-limit.backend=memory")
                .run(context -> assertThat(context).doesNotHaveBean(ChannelOutboundRateLimiter.class));
    }
}
