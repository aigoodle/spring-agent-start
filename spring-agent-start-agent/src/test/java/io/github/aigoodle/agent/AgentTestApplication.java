package io.github.aigoodle.agent;

import io.github.aigoodle.agent.support.FakeEmbeddingProvider;
import io.github.aigoodle.agent.support.ScriptedChatProvider;
import io.github.aigoodle.model.provider.ModelProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test context: model + tools + agent auto-config, plus a scripted chat provider so
 * agent strategies can be exercised deterministically offline.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class AgentTestApplication {

    @Bean
    public ModelProvider scriptedChatProvider() {
        return new ScriptedChatProvider();
    }

    @Bean
    public ModelProvider fakeEmbeddingProvider() {
        return new FakeEmbeddingProvider();
    }
}
