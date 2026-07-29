package io.github.aigoodle.observability;

import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.observability.support.UsageChatProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootConfiguration
@EnableAutoConfiguration
public class ObservabilityTestApplication {

    @Bean
    public ModelProvider usageChatProvider() {
        return new UsageChatProvider();
    }
}
