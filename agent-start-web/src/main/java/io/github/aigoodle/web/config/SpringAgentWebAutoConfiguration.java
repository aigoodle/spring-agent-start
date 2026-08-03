package io.github.aigoodle.web.config;

import io.github.aigoodle.web.common.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auto-config for the REST web layer. Every controller lives under
 * {@code io.github.aigoodle.web.controller} and is picked up by component scan;
 * this class only wires the cross-cutting concerns (CORS, global exception handler,
 * properties). Runs on Spring MVC (servlet) — the reactive AI-chat SSE stack sits
 * in the sibling {@code agent-start-completion} module.
 * <p>
 * The auto-config works on <b>both</b> web stacks. Stack-specific beans live in the
 * nested {@link ServletSupport} / {@link ReactiveSupport} configuration classes, each
 * guarded by {@code @ConditionalOnClass}. A reactive host that excludes
 * {@code spring-boot-starter-web} (such as {@code agent-start-server}) therefore skips
 * the {@link WebMvcConfigurer} beans without ever loading a servlet-only class — and
 * still gets the equivalent path prefix + CORS setup from the {@link WebFluxConfigurer}
 * mirror. Keep stack-typed bean methods inside those nested classes: a servlet/reactive
 * type in a signature of the <em>outer</em> class would make bean-definition
 * introspection blow up with {@code NoClassDefFoundError} on the other stack.
 * <p>
 * Controllers here use class-level {@code @ConditionalOnBean(XxxService.class)} to
 * degrade gracefully when a runtime module is absent. That condition is evaluated
 * during component scan, so this autoconfig must be processed <em>after</em> the
 * runtime autoconfigs that register those service beans — otherwise the condition
 * sees no bean definition and the controller is silently dropped.
 * {@code afterName} pins the order without a compile-time module dependency
 * (all runtime modules are {@code optional}, so a hard class reference would break
 * consumers that opt out of, say, the workflow module).
 */
@AutoConfiguration(afterName = {
        "io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration",
        "io.github.aigoodle.tool.config.SpringAgentToolsAutoConfiguration",
        "io.github.aigoodle.agent.config.SpringAgentAgentAutoConfiguration",
        "io.github.aigoodle.knowledge.config.SpringAgentKnowledgeAutoConfiguration",
        "io.github.aigoodle.workflow.config.SpringAgentWorkflowAutoConfiguration",
        "io.github.aigoodle.trigger.config.SpringAgentTriggerAutoConfiguration",
        "io.github.aigoodle.observability.config.SpringAgentObservabilityAutoConfiguration"
})
@EnableConfigurationProperties(SpringAgentWebProperties.class)
@ComponentScan(basePackages = {
        "io.github.aigoodle.web.controller",
        "io.github.aigoodle.web.service",
        "io.github.aigoodle.web.support"
})
public class SpringAgentWebAutoConfiguration {

    /**
     * Fixed URL prefix stamped in front of every controller under
     * {@code io.github.aigoodle.web.controller}. The starter is designed to
     * be embedded in third-party apps; a namespace-avoidance prefix keeps our
     * paths from colliding with the host's own routes. Hard-coded — the value
     * only exists to be unique, not to be tuned.
     */
    public static final String CONTROLLER_PATH_PREFIX = "/agent-start";

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler springAgentGlobalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * Resolves the configured allowed CORS origins, defaulting to a single
     * wildcard. Shared by the servlet and reactive CORS configurers so both
     * stacks stay behaviourally identical.
     */
    static String[] allowedOriginsOf(SpringAgentWebProperties properties) {
        return properties.getAllowedOrigins() == null || properties.getAllowedOrigins().isEmpty()
                ? new String[]{"*"}
                : properties.getAllowedOrigins().toArray(new String[0]);
    }

    /**
     * Servlet-stack wiring: path prefix + CORS via {@link WebMvcConfigurer}.
     * Skipped entirely on reactive hosts where spring-webmvc is absent.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class ServletSupport {

        @Bean
        @ConditionalOnMissingBean(name = "springAgentPathPrefixConfigurer")
        public WebMvcConfigurer springAgentPathPrefixConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void configurePathMatch(PathMatchConfigurer configurer) {
                    configurer.addPathPrefix(CONTROLLER_PATH_PREFIX,
                            c -> c.getPackageName().startsWith("io.github.aigoodle.web.controller"));
                }
            };
        }

        @Bean
        @ConditionalOnMissingBean(name = "springAgentCorsConfigurer")
        public WebMvcConfigurer springAgentCorsConfigurer(SpringAgentWebProperties properties) {
            return new WebMvcConfigurer() {
                @Override
                public void addCorsMappings(CorsRegistry registry) {
                    String[] origins = allowedOriginsOf(properties);
                    boolean wildcard = origins.length == 1 && "*".equals(origins[0]);
                    var mapping = registry.addMapping("/**")
                            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                            .allowedHeaders("*")
                            .exposedHeaders("*")
                            .maxAge(3600);
                    if (wildcard) {
                        // allowCredentials + "*" is illegal at the Spring layer; drop credentials for wildcards.
                        mapping.allowedOriginPatterns("*").allowCredentials(false);
                    } else {
                        mapping.allowedOrigins(origins).allowCredentials(true);
                    }
                }
            };
        }
    }

    /**
     * Reactive-stack mirror of {@link ServletSupport}: the same {@code /agent-start}
     * path prefix and CORS policy via {@link WebFluxConfigurer}, so generic
     * {@code @RestController} methods from this module behave identically when the
     * host runs WebFlux/Netty with spring-boot-starter-web excluded (e.g.
     * {@code agent-start-server}). Skipped when spring-webflux is absent.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebFluxConfigurer.class)
    static class ReactiveSupport {

        @Bean
        @ConditionalOnMissingBean(name = "springAgentReactivePathPrefixConfigurer")
        public WebFluxConfigurer springAgentReactivePathPrefixConfigurer() {
            return new WebFluxConfigurer() {
                @Override
                public void configurePathMatching(
                        org.springframework.web.reactive.config.PathMatchConfigurer configurer) {
                    configurer.addPathPrefix(CONTROLLER_PATH_PREFIX,
                            c -> c.getPackageName().startsWith("io.github.aigoodle.web.controller"));
                }
            };
        }

        @Bean
        @ConditionalOnMissingBean(name = "springAgentReactiveCorsConfigurer")
        public WebFluxConfigurer springAgentReactiveCorsConfigurer(SpringAgentWebProperties properties) {
            return new WebFluxConfigurer() {
                @Override
                public void addCorsMappings(org.springframework.web.reactive.config.CorsRegistry registry) {
                    String[] origins = allowedOriginsOf(properties);
                    boolean wildcard = origins.length == 1 && "*".equals(origins[0]);
                    var mapping = registry.addMapping("/**")
                            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                            .allowedHeaders("*")
                            .exposedHeaders("*")
                            .maxAge(3600);
                    if (wildcard) {
                        mapping.allowedOriginPatterns("*").allowCredentials(false);
                    } else {
                        mapping.allowedOrigins(origins).allowCredentials(true);
                    }
                }
            };
        }
    }
}
