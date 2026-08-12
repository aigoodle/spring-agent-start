package io.github.aigoodle.observability.config;

import io.github.aigoodle.agent.config.SpringAgentAgentAutoConfiguration;
import io.github.aigoodle.agent.runtime.AgentRuntime;
import io.github.aigoodle.observability.eval.AgentEvaluationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Agent-level operations beans loaded after the core runtime is available. */
@AutoConfiguration(after = SpringAgentAgentAutoConfiguration.class)
public class SpringAgentOpsAutoConfiguration {

    @Bean
    @ConditionalOnBean(AgentRuntime.class)
    @ConditionalOnMissingBean
    public AgentEvaluationRunner agentEvaluationRunner(AgentRuntime runtime) {
        return new AgentEvaluationRunner(runtime);
    }
}
