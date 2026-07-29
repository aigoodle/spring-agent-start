package io.github.aigoodle.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * OpenAPI 3 auto-config for agent-start-web. Loaded only when springdoc is on
 * the classpath (springdoc-openapi-starter-webflux-ui is declared optional in
 * the library pom; the runnable agent-start-server always pulls it in).
 * <p>
 * External clients hit {@code /v3/api-docs} for the raw spec (auto-generatable
 * TS/Python/Java clients) or {@code /swagger-ui.html} for a browsable UI.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springdoc.core.models.GroupedOpenApi")
@EnableConfigurationProperties(SpringAgentWebProperties.class)
public class SpringAgentOpenApiConfig {

    @Bean
    public OpenAPI springAgentOpenApi(SpringAgentWebProperties properties) {
        // Effective client URL = CONTROLLER_PATH_PREFIX ("/agent-start") + base-path.
        // base-path is expected to be empty ("") in the standard setup so URLs
        // land as /agent-start/xxx (matching the frontend's /api/agent-start after the
        // dev proxy strips /api). If someone still sets base-path, keep the old
        // concatenation so the swagger URL reflects the actual routes.
        String basePath = properties.getBasePath() == null ? "" : properties.getBasePath();
        String serverUrl = SpringAgentWebAutoConfiguration.CONTROLLER_PATH_PREFIX + basePath;
        return new OpenAPI()
                .info(new Info()
                        .title("spring-agent-start REST API")
                        .description("""
                                REST + SSE surface of spring-agent-start — model providers, knowledge base,
                                workflow engine, agent runtime, tools, observability, triggers.

                                All responses use the envelope shape {code, message, data}. Success is
                                {code: 'ok', data: …}; errors are {code: '<code>', message: '<msg>', details?}.
                                See ApiErrorCode for the full enum.""")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("spring-agent-start")
                                .url("https://github.com/agent-start/spring-agent-start"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url(serverUrl).description("Base URL")))
                .tags(List.of(
                        new Tag().name("System").description("Health + module discovery"),
                        new Tag().name("Model").description("Provider credentials + model registration + connection test"),
                        new Tag().name("Knowledge").description("Datasets · documents · segments · retrieval"),
                        new Tag().name("Workflow").description("DAG persistence + synchronous + SSE run"),
                        new Tag().name("Agent").description("Agent CRUD + one-shot chat + SSE chat"),
                        new Tag().name("Tools").description("Tool discovery + test invocation"),
                        new Tag().name("Observability").description("LLM call metrics"),
                        new Tag().name("Trigger").description("Webhook/cron/event triggers")))
                .components(new Components());
    }
}
