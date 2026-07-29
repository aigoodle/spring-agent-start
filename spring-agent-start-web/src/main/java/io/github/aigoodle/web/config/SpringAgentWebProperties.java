package io.github.aigoodle.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the REST web layer.
 * <p>
 * Defaults are frontend-friendly for local development: empty base-path (so
 * every controller lives directly under {@code CONTROLLER_PATH_PREFIX} =
 * {@code /agent-start}), CORS open to all origins. Applications that embed
 * spring-agent-start-web in a secured deployment can lock these down via
 * {@code spring-agent.web.*}.
 */
@Data
@ConfigurationProperties(prefix = "spring-agent.web")
public class SpringAgentWebProperties {

    /**
     * Root path prepended to every REST controller. Empty by default so URLs
     * are {@code /agent-start/agents} not {@code /agent-start/api/v1/agents}, which
     * matches what @aigoodle/ui composables build ({@code /api/agent-start/*}
     * after the frontend proxy strips {@code /api}).
     */
    private String basePath = "";

    /** Allowed origins for CORS; {@code *} = any (default, since no login is expected). */
    private List<String> allowedOrigins = List.of("*");

    /**
     * How large a multipart upload can be (default 50 MiB). Configure the servlet
     * limits via {@code spring.servlet.multipart.max-file-size} in application.yml
     * so the value ends up in {@code MultipartResolver}.
     */
    private String maxUploadSize = "50MB";
}
