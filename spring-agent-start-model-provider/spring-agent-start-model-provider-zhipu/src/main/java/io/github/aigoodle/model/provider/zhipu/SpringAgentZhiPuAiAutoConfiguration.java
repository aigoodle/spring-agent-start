package io.github.aigoodle.model.provider.zhipu;

import io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Publishes a {@link ZhiPuAiModelProvider} bean. Runs before the model auto-config
 * so it lands in the {@code ObjectProvider<ModelProvider>} feed and beats the
 * built-in {@code "zhipu"} preset.
 */
@AutoConfiguration(before = SpringAgentModelAutoConfiguration.class)
@ConditionalOnClass(ZhiPuAiChatModel.class)
public class SpringAgentZhiPuAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "zhipuAiModelProvider")
    public ZhiPuAiModelProvider zhipuAiModelProvider() {
        return new ZhiPuAiModelProvider();
    }
}
