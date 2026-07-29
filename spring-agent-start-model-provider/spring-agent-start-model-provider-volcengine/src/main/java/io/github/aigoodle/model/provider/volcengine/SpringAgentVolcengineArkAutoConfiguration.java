package io.github.aigoodle.model.provider.volcengine;

import io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = SpringAgentModelAutoConfiguration.class)
@ConditionalOnClass(OpenAiChatModel.class)
public class SpringAgentVolcengineArkAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "volcengineArkModelProvider")
    public VolcengineArkModelProvider volcengineArkModelProvider() {
        return new VolcengineArkModelProvider();
    }
}
