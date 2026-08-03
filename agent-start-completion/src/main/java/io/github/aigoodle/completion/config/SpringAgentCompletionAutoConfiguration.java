package io.github.aigoodle.completion.config;

import io.github.aigoodle.agent.service.ApiTokenService;
import io.github.aigoodle.completion.support.AppAccessResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-config for the reactive AI chat surface. Every controller and service
 * lives under {@code io.github.aigoodle.completion.*} and is picked up by
 * this component scan. Streaming endpoints ({@code /chat/completions/{appId}},
 * {@code /chat-messages}) run on Spring WebFlux / Netty so token-level SSE
 * doesn't tie up a servlet thread per client.
 * <p>
 * {@code AppGenerateService} and {@code ChatController} carry class-level
 * {@code @ConditionalOnBean(AgentService.class)} / {@code @ConditionalOnBean(AppGenerateService.class)}.
 * Those conditions are evaluated during the {@code @ComponentScan} above; if
 * this autoconfig runs before the agent / workflow runtime autoconfigs have
 * registered their beans, the whole chat surface silently vanishes.
 * {@code afterName} pins the order without a compile-time dependency on the
 * runtime modules (which are {@code optional} by design).
 */
@AutoConfiguration(afterName = {
        "io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration",
        "io.github.aigoodle.tool.config.SpringAgentToolsAutoConfiguration",
        "io.github.aigoodle.agent.config.SpringAgentAgentAutoConfiguration",
        "io.github.aigoodle.workflow.config.SpringAgentWorkflowAutoConfiguration"
})
@ComponentScan(basePackages = {
        "io.github.aigoodle.completion.controller",
        "io.github.aigoodle.completion.service"
})
public class SpringAgentCompletionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AppAccessResolver appAccessResolver(
            ObjectProvider<ApiTokenService> apiTokenServices) {
        return new AppAccessResolver(apiTokenServices);
    }
}
