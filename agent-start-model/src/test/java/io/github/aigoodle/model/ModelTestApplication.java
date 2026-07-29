package io.github.aigoodle.model;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal context for the model-module integration tests: enables auto-configuration
 * (which loads {@code SpringAgentModelAutoConfiguration} via the imports file) without
 * component scanning the production packages.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class ModelTestApplication {
}
